package org.example;

import org.example.admin.AdminControlEngine;
import org.example.admin.AuditLog;
import org.example.core.AnswerModel;
import org.example.core.DocumentIngestor;
import org.example.core.IngestionException;
import org.example.core.Workbench;
import org.example.index.InvertedIndex;
import org.example.ingest.DocumentParser;
import org.example.input.KeyMapRegistry.KeyStroke;
import org.example.model.DocumentType;
import org.example.net.WebSearchBridge;
import org.example.net.WebSearchBridge.HybridOutcome;
import org.example.platform.OsShellBridge;
import org.example.preview.PreviewDocument;
import org.example.preview.PreviewResult;
import org.example.preview.PreviewService;
import org.example.project.ProjectProfile;
import org.example.project.ProjectWorkspaceManager;
import org.example.quota.QuotaManager;
import org.example.repl.InternalTerminalEngine;
import org.example.repl.InternalTerminalEngine.Outcome;
import org.example.state.FocusModeCoordinator;
import org.example.state.FocusModeCoordinator.Element;
import org.example.state.FocusModeCoordinator.Severity;
import org.example.state.PanelStateCoordinator;
import org.example.state.PanelStateCoordinator.Panel;
import org.example.state.ViewModeCoordinator;
import org.example.state.ViewModeCoordinator.ViewMode;
import org.example.workbench.ExtendedWorkbenchController;
import org.example.workbench.WorkbenchController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Headless verification of features 6–10: preview size guard and lifecycle, focus/quiet mode, admin policy and
 * access control, the metered web bridge (fake HTTP transport and fake model — no network), and project isolation
 * with atomic switching and rollback. Nothing is opened, nothing leaves the machine.
 *
 * <pre>gradle -q runExtendedTests</pre>
 */
public final class ExtendedTestRunner {

    private int passed;
    private final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        ExtendedTestRunner runner = new ExtendedTestRunner();
        Path root = Files.createTempDirectory("dwb-ext-");
        try {
            runner.run(root);
        } finally {
            deleteRecursively(root);
        }
        System.out.println();
        System.out.printf("%d passed, %d failed%n", runner.passed, runner.failures.size());
        runner.failures.forEach(f -> System.out.println("  FAILED: " + f));
        System.out.println("jvm: " + ViewModeCoordinator.MemorySnapshot.now().describe());
        System.exit(runner.failures.isEmpty() ? 0 : 1);
    }

    private void run(Path root) throws Exception {
        Path docs = Files.createDirectories(root.resolve("alpha docs"));
        writeCorpus(docs);
        previewPipeline(root.resolve("preview"), docs);
        focusMode();
        webBridge();
        adminEngine(root.resolve("admin"), docs);
        projectsAndIntegration(root.resolve("home"), docs, root.resolve("beta docs"));
    }

    // ================================================================== 6. preview

    private void previewPipeline(Path dir, Path docs) throws Exception {
        section("Preview: preflight size guard, bounded extraction, lifecycle");
        Files.createDirectories(dir);
        PreviewService service = new PreviewService();

        Path huge = dir.resolve("huge.txt");
        writeBytes(huge, (5L << 20) + 1);
        long t0 = System.nanoTime();
        PreviewResult tooLarge = service.preview(huge);
        double ms = (System.nanoTime() - t0) / 1e6;
        check("5 MB + 1 byte TXT is TOO_LARGE with exact size", tooLarge instanceof PreviewResult.TooLarge t
                && t.sizeBytes() == (5L << 20) + 1 && t.limitBytes() == 5L << 20
                && t.status() == PreviewResult.Status.TOO_LARGE, tooLarge.describe());
        check("rejection is instant (no parsing)", ms < 200, String.format(Locale.ROOT, "%.1f ms", ms));
        check("describe shows the exact byte count", tooLarge.describe().contains("5,242,881 bytes"),
                tooLarge.describe());

        EnumMap<DocumentType, Long> small = new EnumMap<>(DocumentType.class);
        for (DocumentType t : DocumentType.values()) {
            small.put(t, 10L << 20);
        }
        small.put(DocumentType.PDF, 2_000L);
        PreviewService strict = new PreviewService(new PreviewService.Limits(small, 64_000, 1_500, 1_000, 12));
        Path pdf = dir.resolve("scan.pdf");
        Files.writeString(pdf, "%PDF-1.4\n" + "x".repeat(3_000));
        check("per-format limit applies (PDF limit 2000 B)",
                strict.preview(pdf) instanceof PreviewResult.TooLarge t && t.type() == DocumentType.PDF, "");
        Path fakePdf = dir.resolve("fake.pdf");
        Files.writeString(fakePdf, "not a pdf at all");
        check("a .pdf without PDF header fails cleanly", service.preview(fakePdf) instanceof PreviewResult.Failed f
                && f.reason() == IngestionException.Reason.CORRUPTED, service.preview(fakePdf).describe());
        check("unknown types are unsupported", service.preview(Files.writeString(dir.resolve("x.exe"), "MZ"))
                instanceof PreviewResult.Unsupported, "");

        Path big = dir.resolve("uzun.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; sb.length() < 400_000; i++) {
            sb.append("Satır ").append(i).append(": kira bedeli ve teminat hükümleri burada yer alır.\n");
        }
        Files.writeString(big, sb.toString(), StandardCharsets.UTF_8);
        PreviewResult ready = service.preview(big);
        PreviewDocument doc = ready instanceof PreviewResult.Ready r ? r.document() : null;
        check("400 KB text previews as READY", doc != null, ready.describe());
        if (doc == null) {
            return;
        }
        check("only the beginning is kept (bounded chars)", doc.truncated() && doc.retainedChars() <= 66_000,
                doc.retainedChars() + " chars");
        check("line cap respected", doc.lineCount() <= 1_500, doc.lineCount() + " lines");
        check("cutoff notice is the last line", doc.line(doc.lineCount() - 1) instanceof PreviewDocument.Notice, "");
        check("service accounts retained chars", service.retainedChars() == doc.retainedChars(), "");

        PreviewResult csv = service.preview(docs.resolve("tablo.csv"));
        PreviewDocument table = csv instanceof PreviewResult.Ready r ? r.document() : null;
        check("opening a new preview closes the previous one", doc.closed() && doc.retainedChars() == 0, "");
        boolean accessAfterClose;
        try {
            doc.line(0);
            accessAfterClose = true;
        } catch (IllegalStateException e) {
            accessAfterClose = false;
        }
        check("closed preview refuses access", !accessAfterClose, "");
        List<PreviewDocument.PreviewLine> rows = table == null ? List.of() : table.lines(0, 50);
        check("CSV preview yields a header row and data rows",
                rows.stream().anyMatch(l -> l instanceof PreviewDocument.TableRow t && t.header()
                        && t.cells().contains("B: Tutar"))
                        && rows.stream().anyMatch(l -> l instanceof PreviewDocument.TableRow t && !t.header()
                        && t.cells().contains("Ayşe")), rows.toString());
        service.dismiss();
        check("dismiss returns memory to baseline", service.retainedChars() == 0 && service.active().isEmpty()
                && table != null && table.closed(), "retained " + service.retainedChars());

        PreviewResult first = service.previewAsync(big).get(10, TimeUnit.SECONDS);
        service.previewAsync(docs.resolve("kira.txt")).get(10, TimeUnit.SECONDS);
        check("async: latest request owns the pane", service.active().map(d -> d.path().getFileName().toString())
                .orElse("").equals("kira.txt"), "");
        check("async: earlier preview released", !(first instanceof PreviewResult.Ready r) || r.document().closed(), "");
        service.close();
    }

    // ================================================================== 7. focus

    private void focusMode() {
        section("Focus / zen mode and quiet mode");
        PanelStateCoordinator panels = new PanelStateCoordinator();
        ViewModeCoordinator view = new ViewModeCoordinator();
        panels.show(Panel.TERMINAL);
        view.setMode(ViewMode.DETAILED);
        FocusModeCoordinator focus = new FocusModeCoordinator(panels, view);
        List<String> events = new ArrayList<>();
        focus.onChange((a, b) -> events.add(a.focus() + "->" + b.focus()));

        check("enter focus", focus.enter(), "");
        check("peripheral panels leave the layout", !panels.isShown(Panel.FILE_TREE) && !panels.isShown(Panel.TERMINAL)
                && panels.isShown(Panel.RESULTS), panels.snapshot().toString());
        check("telemetry suppressed (simple view)", view.mode() == ViewMode.SIMPLE, "");
        check("only search bar and evidence stream remain visible",
                focus.isVisible(Element.SEARCH_BAR) && focus.isVisible(Element.EVIDENCE_STREAM)
                        && !focus.isVisible(Element.TELEMETRY) && !focus.isVisible(Element.BADGES)
                        && !focus.isVisible(Element.TOOLBAR) && !focus.isVisible(Element.STATUS_BAR), "");
        check("quiet mode implied", focus.quiet(), "");
        check("non-critical notifications suppressed, critical pass",
                !focus.allowNotification(Severity.INFO) && !focus.allowNotification(Severity.WARNING)
                        && focus.allowNotification(Severity.CRITICAL), "");
        check("debug logs gated, warnings pass", !focus.allowLog(Severity.DEBUG) && focus.allowLog(Severity.WARNING), "");
        check("entering twice is a no-op", !focus.enter() && events.size() == 1, events.toString());
        focus.exit();
        check("exit restores panels exactly", panels.isShown(Panel.TERMINAL) && panels.isShown(Panel.FILE_TREE), "");
        check("exit restores the detailed view", view.mode() == ViewMode.DETAILED, "");
        check("quiet mode ends with focus", !focus.quiet() && focus.allowNotification(Severity.INFO), "");
        check("suppressed notifications counted", focus.drainSuppressedNotifications() == 2, "");
        focus.setQuiet(true);
        check("quiet mode works without focus", focus.quiet() && !focus.focusActive(), "");
    }

    // ================================================================== 9. web

    private void webBridge() {
        section("Web bridge: BM25 shield, quota gate, graceful downgrade (fake transport)");
        InvertedIndex local = localIndex();
        FakeTransport transport = new FakeTransport(SEARXNG_JSON);
        FakeModel model = new FakeModel();
        QuotaManager quota = new QuotaManager(new QuotaManager.Config(5, 5, Duration.ofDays(1), null, null,
                EnumSet.allOf(DayOfWeek.class), ZoneId.of("UTC")));
        WebSearchBridge bridge = new WebSearchBridge(webConfig(), transport, () -> local, quota,
                () -> Optional.of(model), Clock.systemUTC());

        check("@web prefix detection", WebSearchBridge.isWebQuery("@web kira") && WebSearchBridge.isWebQuery("  @WEB x")
                && !WebSearchBridge.isWebQuery("@website") && !WebSearchBridge.isWebQuery("kira @web"), "");
        StringBuilder streamed = new StringBuilder();
        HybridOutcome answered = bridge.ask("@web kira artış oranı", streamed::append);
        check("answered from distilled snippets", answered instanceof HybridOutcome.Answered, answered.toString());
        if (answered instanceof HybridOutcome.Answered a) {
            check("shield dropped irrelevant results", a.distillation().kept() == 2 && a.distillation().fetched() == 5,
                    a.distillation().toString());
            check("prompt carries only relevant web text", model.lastPrompt.contains("TÜFE")
                    && !model.lastPrompt.contains("futbol") && !model.lastPrompt.contains("mercimek"), model.lastPrompt);
            check("prompt includes local evidence", model.lastPrompt.contains("[S1]")
                    && a.sources().stream().anyMatch(s -> s.tag().startsWith("S")), "");
            check("answer streamed to the sink", streamed.toString().contains("[W1]"), streamed.toString());
            check("quota lease consumed and usage recorded", quota.snapshot().used() == 1
                    && quota.snapshot().promptTokens() > 0, quota.snapshot().toString());
        }

        WebSearchBridge noModel = new WebSearchBridge(webConfig(), transport, () -> local, quota, Optional::empty,
                Clock.systemUTC());
        check("without a model: snippets only, no quota used", noModel.ask("@web kira artış", d -> { })
                instanceof HybridOutcome.SnippetsOnly s && s.snippets().size() == 2 && quota.snapshot().used() == 1, "");

        int callsBefore = transport.calls.get();
        HybridOutcome irrelevant = bridge.ask("@web kuantum bilgisayar", d -> { });
        check("no relevant snippet: model not called", irrelevant instanceof HybridOutcome.LocalOnly l
                && l.reason() == WebSearchBridge.Downgrade.NO_WEB_EVIDENCE && model.calls == 1, irrelevant.toString());
        check("that query did reach the provider", transport.calls.get() == callsBefore + 1, "");

        QuotaManager exhausted = new QuotaManager(new QuotaManager.Config(1, 5, Duration.ofDays(1), null, null,
                EnumSet.allOf(DayOfWeek.class), ZoneId.of("UTC")));
        exhausted.acquire();
        WebSearchBridge metered = new WebSearchBridge(webConfig(), transport, () -> local, exhausted,
                () -> Optional.of(model), Clock.systemUTC());
        int before = transport.calls.get();
        HybridOutcome denied = metered.ask("@web kira artış", d -> { });
        check("quota exhausted: local-only, network untouched", denied instanceof HybridOutcome.LocalOnly l
                && l.reason() == WebSearchBridge.Downgrade.QUOTA_EXHAUSTED && !l.local().isEmpty()
                && transport.calls.get() == before, denied.toString());

        // Sunday 12:00 UTC with a Mon-Fri 09-18 window.
        Clock sunday = Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneOffset.UTC);
        QuotaManager officeHours = new QuotaManager(new QuotaManager.Config(10, 5, Duration.ofDays(1),
                LocalTime.of(9, 0), LocalTime.of(18, 0), EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
                ZoneId.of("UTC")), sunday);
        WebSearchBridge weekend = new WebSearchBridge(webConfig(), transport, () -> local, officeHours,
                () -> Optional.of(model), sunday);
        check("outside business hours: local-only", weekend.ask("@web kira", d -> { })
                instanceof HybridOutcome.LocalOnly l && l.reason() == WebSearchBridge.Downgrade.OUTSIDE_BUSINESS_HOURS, "");

        FakeTransport offline = new FakeTransport(null);
        offline.failWith = new ConnectException("Connection refused");
        WebSearchBridge unplugged = new WebSearchBridge(webConfig(), offline, () -> local, quota,
                () -> Optional.of(model), Clock.systemUTC());
        HybridOutcome o1 = unplugged.ask("@web kira artış", d -> { });
        HybridOutcome o2 = unplugged.ask("@web kira artış", d -> { });
        check("offline: downgrade with local results", o1 instanceof HybridOutcome.LocalOnly l
                && l.reason() == WebSearchBridge.Downgrade.OFFLINE && !l.local().isEmpty(), o1.toString());
        check("offline back-off skips the network on the next query", o2 instanceof HybridOutcome.LocalOnly
                && offline.calls.get() == 1, "calls " + offline.calls.get());

        FakeTransport broken = new FakeTransport("{}");
        broken.status = 503;
        check("HTTP 5xx: WEB_ERROR downgrade", new WebSearchBridge(webConfig(), broken, () -> local, quota,
                () -> Optional.of(model), Clock.systemUTC()).ask("@web kira", d -> { })
                instanceof HybridOutcome.LocalOnly l && l.reason() == WebSearchBridge.Downgrade.WEB_ERROR, "");
        check("not configured: NOT_CONFIGURED downgrade", new WebSearchBridge(WebSearchBridge.Config.disabled(),
                transport, () -> local, quota, () -> Optional.of(model), Clock.systemUTC()).ask("@web kira", d -> { })
                instanceof HybridOutcome.LocalOnly l && l.reason() == WebSearchBridge.Downgrade.NOT_CONFIGURED, "");

        FakeTransport braveTransport = new FakeTransport(BRAVE_JSON);
        WebSearchBridge brave = new WebSearchBridge(new WebSearchBridge.Config(WebSearchBridge.Provider.BRAVE,
                URI.create("https://api.search.brave.com"), "key-123", null, 8, 512 << 10, 600, 4, 4_000, null),
                braveTransport, () -> local, quota, Optional::empty, Clock.systemUTC());
        try {
            List<WebSearchBridge.WebSnippet> s = brave.search("kira");
            check("Brave JSON parsed, HTML stripped", s.size() == 1 && s.getFirst().text().equals("Kira artış oranı <%25> & TÜFE"),
                    s.toString());
        } catch (WebSearchBridge.WebException e) {
            check("Brave JSON parsed, HTML stripped", false, e.getMessage());
        }
        java.net.http.HttpRequest sent = braveTransport.last;
        check("Brave request carries the subscription token and encoded query", sent != null
                && sent.headers().firstValue("X-Subscription-Token").orElse("").equals("key-123")
                && sent.uri().toString().contains("/res/v1/web/search?count=8&q=kira"), String.valueOf(sent));
        check("SearXNG request asks for JSON", transport.last != null
                && transport.last.uri().toString().startsWith("http://searx.intranet/search?format=json"),
                String.valueOf(transport.last));
    }

    // ================================================================== 8. admin

    private void adminEngine(Path dir, Path docs) throws Exception {
        section("Admin engine: exclusions, limits, access control, health, audit");
        Files.createDirectories(dir);
        MutableClock clock = new MutableClock(Instant.parse("2026-09-21T09:00:00Z"));
        AuditLog audit = new AuditLog(dir.resolve("audit.log"), 64 << 10, 2, clock);
        AdminControlEngine admin = new AdminControlEngine(dir.resolve("admin.properties"), audit, clock, null);
        check("first load writes defaults", admin.load().isEmpty() && Files.isRegularFile(dir.resolve("admin.properties")), "");
        check("default exclusions", admin.excluded(Path.of("C:/proj/node_modules/lib/readme.txt"))
                && admin.excluded(Path.of("C:/proj/.git/config")) && admin.excluded(Path.of("C:/x/~$rapor.docx"))
                && admin.excluded(Path.of("C:/x/cache.TMP")) && !admin.excluded(Path.of("C:/x/rapor.docx")), "");

        Workbench wb = new Workbench(new DocumentParser(), new InvertedIndex(), null);
        try (WorkbenchController c = new WorkbenchController(wb, WorkbenchController.Config.defaults(dir.resolve("data")),
                new OsShellBridge(OsShellBridge.Platform.WINDOWS, recorder(new ArrayList<>()), false))) {
            c.setIngestPolicy(admin.ingestPolicy());
            c.onEvent(admin.eventRecorder());
            admin.setWorkers(2);
            InternalTerminalEngine.Output sink = c.terminal().output();
            int added = c.addPath(docs, sink);
            check("walk prunes node_modules, skips ~$ files; 2 workers", added == 3
                    && c.workbench().documents().stream().noneMatch(d -> d.fileName().contains("gizli")), "added " + added);
            boolean refused;
            try {
                c.ingest(docs.resolve("node_modules").resolve("gizli.txt"));
                refused = false;
            } catch (IngestionException e) {
                refused = e.getMessage().contains("excluded");
            }
            check("direct ingest of an excluded path is refused", refused, "");
            check("corrupt PDF failure audited", audit.tail(50).stream()
                    .anyMatch(l -> l.contains("INGEST_FAILURE") && l.contains("bozuk.pdf")), String.join("\n", audit.tail(10)));

            Path victim = Files.writeString(dir.resolve("gecici-not.txt"), "geçici not metni", StandardCharsets.UTF_8);
            c.ingest(victim);
            Files.delete(victim);
            AdminControlEngine.HealthReport health = admin.healthAudit(c);
            check("health audit flags missing sources", health.findings().stream()
                    .anyMatch(f -> f.code().equals("MISSING_SOURCES")) && health.worst() == AdminControlEngine.Severity.WARN,
                    health.findings().toString());
            check("health audit reports metrics", health.metrics().get("documents") == 4, health.metrics().toString());
        }

        admin.setPassphrase("dogru-parola-42".toCharArray());
        boolean blocked;
        try {
            admin.addExclusion("*.log");
            blocked = false;
        } catch (AdminControlEngine.PrivilegeException e) {
            blocked = true;
        }
        check("privileged op blocked while locked", blocked, "");
        for (int i = 0; i < 4; i++) {
            admin.unlock("yanlis".toCharArray());
        }
        check("fifth failure locks out", admin.unlock("yanlis".toCharArray()) instanceof AdminControlEngine.UnlockResult.LockedOut, "");
        check("even the right passphrase waits out the lockout",
                admin.unlock("dogru-parola-42".toCharArray()) instanceof AdminControlEngine.UnlockResult.LockedOut, "");
        clock.advance(Duration.ofSeconds(61));
        check("unlock after lockout", admin.unlock("dogru-parola-42".toCharArray())
                instanceof AdminControlEngine.UnlockResult.Unlocked && admin.unlocked(), "");
        check("exclusion added when unlocked", admin.addExclusion("*.log"), "");
        clock.advance(Duration.ofMinutes(11));
        check("session expires", !admin.unlocked(), "");
        boolean badCeiling;
        try {
            admin.unlock("dogru-parola-42".toCharArray());
            admin.setMemoryCeiling(0.99);
            badCeiling = false;
        } catch (IllegalArgumentException e) {
            badCeiling = true;
        }
        check("out-of-range memory ceiling rejected", badCeiling, "");
        check("gc report", admin.forceGc().millis() >= 0, "");

        AdminControlEngine reloaded = new AdminControlEngine(dir.resolve("admin.properties"),
                new AuditLog(dir.resolve("audit2.log")), clock, null);
        reloaded.load();
        check("policy persisted (exclusion, workers, passphrase hash)", reloaded.exclusions().contains("*.log")
                && reloaded.settings().workers() == 2 && reloaded.protectedMode() && !reloaded.unlocked(), "");
        String props = Files.readString(dir.resolve("admin.properties"));
        check("passphrase never stored in clear text", !props.contains("dogru-parola-42") && props.contains("pbkdf2$"), "");
        List<String> tail = audit.tail(200);
        check("audit records denials and grants", tail.stream().anyMatch(l -> l.contains("SECURITY") && l.contains("denied"))
                && tail.stream().anyMatch(l -> l.contains("granted")), "");
        check("audit log never contains the passphrase", tail.stream().noneMatch(l -> l.contains("dogru-parola")), "");
        AuditLog injected = new AuditLog(dir.resolve("inject.log"));
        injected.record(AuditLog.Level.INFO, AuditLog.Category.FILE_ACCESS, "file", "evil\n2026 FAKE ENTRY");
        check("CR/LF in values cannot forge log lines", injected.tail(10).size() == 1, injected.tail(10).toString());
        injected.close();
        reloaded.close();
        admin.close();
    }

    // ================================================================== 10. projects + integration

    private void projectsAndIntegration(Path home, Path alphaDocs, Path betaDocs) throws Exception {
        section("Projects + extended controller: isolation, atomic switch, rollback, REPL wiring");
        Files.createDirectories(betaDocs);
        Files.writeString(betaDocs.resolve("tedarik.txt"), "Tedarik sözleşmesi: teslimat süresi on beş iş günüdür.",
                StandardCharsets.UTF_8);
        CountingIngestor counting = new CountingIngestor();
        List<List<String>> launched = new CopyOnWriteArrayList<>();
        FakeModel model = new FakeModel();
        FakeTransport transport = new FakeTransport(SEARXNG_JSON);
        List<String> toasts = new CopyOnWriteArrayList<>();
        ExtendedWorkbenchController.Services services = new ExtendedWorkbenchController.Services(counting,
                new QuotaManager(QuotaManager.Config.defaults()), () -> Optional.of(model),
                () -> new OsShellBridge(OsShellBridge.Platform.WINDOWS, recorder(launched), false), transport, null, null);
        ExtendedWorkbenchController.Config config = new ExtendedWorkbenchController.Config(home, "Alpha", null,
                webConfig(), Duration.ofMillis(200), true);

        try (ExtendedWorkbenchController ext = new ExtendedWorkbenchController(config, services)) {
            ProjectWorkspaceManager.SwitchReport start = ext.start();
            ext.setNotifier(toasts::add);
            check("first start creates and opens the default project", start.to().name().equals("Alpha")
                    && Files.isRegularFile(home.resolve("projects/alpha/project.dwbproj")), start.describe());
            InternalTerminalEngine t = ext.terminal();
            t.execute("index --add \"" + alphaDocs + "\"");
            WorkbenchController alpha = ext.controller();
            check("admin policy active in project (3 of 6 files)", alpha.workbench().documents().size() == 3,
                    tail(t));

            t.execute("search kira");
            Outcome pv = t.execute("preview 1");
            check("preview command renders the selected hit", pv.succeeded()
                    && t.buffer().snapshot().stream().anyMatch(l -> l.startsWith("READY kira.txt")), tail(t));
            t.execute("preview --close");
            check("preview --close releases it", ext.preview().retainedChars() == 0, "");
            alpha.dispatch(KeyStroke.parse("F3", false).orElseThrow());
            waitFor(() -> ext.preview().active().isPresent());
            check("F3 previews the selected row", ext.preview().active().isPresent(), "");

            alpha.dispatch(KeyStroke.parse("Ctrl+Shift+F", true).orElseThrow());
            check("Ctrl+Shift+F enters focus mode", ext.focus().focusActive()
                    && !alpha.panels().isShown(Panel.FILE_TREE), "");
            check("quiet mode swallows info toasts", !ext.notify(Severity.INFO, "indexed 3 files") && toasts.isEmpty(), "");
            t.execute("focus --off");
            check("focus --off restores the layout", !ext.focus().focusActive() && alpha.panels().isShown(Panel.FILE_TREE), "");

            Outcome webOut = t.execute("@web kira artış oranı");
            check("@web command answers with sources", webOut.succeeded()
                    && t.buffer().snapshot().stream().anyMatch(l -> l.contains("[W1]")), tail(t));

            t.execute("open 1");
            t.execute("admin passwd cok-gizli-parola");
            Outcome blocked = t.execute("blacklist --add *.log");
            check("privileged command refused while locked", blocked instanceof Outcome.Failed, blocked.toString());
            t.execute("admin unlock yanlis-parola");
            t.execute("admin unlock cok-gizli-parola");
            Outcome allowed = t.execute("blacklist --add *.log");
            check("privileged command allowed after unlock", allowed.succeeded(), allowed.toString());
            check("secrets redacted from terminal and history", t.buffer().snapshot().stream()
                    .noneMatch(l -> l.contains("cok-gizli") || l.contains("yanlis-parola"))
                    && t.history().contains("admin unlock ***") && t.history().stream().noneMatch(h -> h.contains("gizli")),
                    t.history().toString());
            Outcome badLimit = t.execute("limit --memory 0.99");
            check("invalid limit fails cleanly", badLimit instanceof Outcome.Failed, "");
            t.execute("audit --tail 200");
            List<String> auditLines = t.buffer().snapshot();
            check("audit tail shows ingest failure, file access, auth and web entries",
                    auditLines.stream().anyMatch(l -> l.contains("INGEST_FAILURE"))
                            && auditLines.stream().anyMatch(l -> l.contains("FILE_ACCESS") && l.contains("OPEN"))
                            && auditLines.stream().anyMatch(l -> l.contains("AUTH"))
                            && auditLines.stream().anyMatch(l -> l.contains("WEB_QUERY")), tail(t));
            check("audit.log never contains the passphrase",
                    !Files.readString(home.resolve("audit.log")).contains("cok-gizli"), "");

            Path lease = alphaDocs.resolve("kira.txt");
            Files.writeString(lease, Files.readString(lease) + "\nEk: depozito üç aylık kiradır.\n", StandardCharsets.UTF_8);
            Files.setLastModifiedTime(lease, FileTime.fromMillis(System.currentTimeMillis() + 3_000));
            waitFor(() -> !alpha.search("depozito", 5).isEmpty());
            check("project watcher re-indexes a changed source", !alpha.search("depozito", 5).isEmpty(), tail(t));

            t.execute("project --new Beta");
            int parsesBeforeSwitch = counting.parses.get();
            Outcome sw = t.execute("project-switch Beta");
            check("project-switch succeeded", sw.succeeded(), sw.toString());
            WorkbenchController beta = ext.controller();
            check("new controller, empty isolated index", beta != alpha && beta.workbench().documents().isEmpty(), "");
            check("old project's terminal is closed", alpha.terminal().submit("stats").join() instanceof Outcome.Failed, "");
            check("switch summary printed in the new terminal", ext.terminal().buffer().snapshot().stream()
                    .anyMatch(l -> l.startsWith("project 'Beta' active")), tail(ext.terminal()));
            InternalTerminalEngine bt = ext.terminal();
            bt.execute("index --add \"" + betaDocs + "\"");
            check("beta cannot see alpha documents", beta.search("kira", 5).isEmpty()
                    && !beta.search("teslimat", 5).isEmpty(), "");
            bt.execute("set-key TOGGLE_MODE Ctrl+Shift+M");
            check("focus/admin/preview commands available in the new project",
                    bt.execute("focus --toggle").succeeded() && bt.execute("audit --health").succeeded()
                            && bt.execute("preview --limits").succeeded(), tail(bt));

            ProjectWorkspaceManager.SwitchReport back = ext.projects().switchTo(ext.projects().find("Alpha").orElseThrow());
            WorkbenchController alphaAgain = ext.controller();
            check("switch back warms alpha from its snapshot", back.startup().warmed() == 3
                    && alphaAgain.workbench().documents().size() == 3, back.describe());
            check("switching never re-parsed a document", counting.parses.get() == parsesBeforeSwitch + 1,
                    "parses " + parsesBeforeSwitch + " -> " + counting.parses.get());
            check("alpha keeps its watched edit", !alphaAgain.search("depozito", 5).isEmpty(), "");
            check("focus mode was left when beta unloaded", !ext.focus().focusActive(), "");
            check("key bindings are per project", alphaAgain.keys().strokeFor(org.example.input.KeyMapRegistry.Action.TOGGLE_MODE)
                    .map(Object::toString).orElse("").equals("Ctrl+M")
                    && Files.readString(home.resolve("projects/beta/keybindings.properties")).contains("Ctrl+Shift+M"), "");
            check("each project has its own manifest", Files.readAllLines(home.resolve("projects/alpha/manifest.tsv")).size() == 4
                    && Files.readString(home.resolve("projects/beta/manifest.tsv")).contains("tedarik.txt"), "");
            check("each project has its own snapshot", Files.isRegularFile(home.resolve("projects/alpha/index.dwb"))
                    && Files.isRegularFile(home.resolve("projects/beta/index.dwb")), "");
            System.out.println("  (" + back.describe() + ")");

            ProjectProfile gamma = ext.projects().create("Gamma", null);
            Files.delete(gamma.projectFile());
            boolean rolledBack;
            try {
                ext.projects().switchTo(gamma);
                rolledBack = false;
            } catch (ProjectWorkspaceManager.SwitchException e) {
                rolledBack = e.rolledBack();
            }
            check("failed switch rolls back to the previous project", rolledBack
                    && ext.projects().active().orElseThrow().profile().name().equals("Alpha")
                    && ext.controller().workbench().documents().size() == 3, "");
            check("project --list shows the catalogue", ext.terminal().execute("project --list").succeeded()
                    && ext.terminal().buffer().snapshot().stream().anyMatch(l -> l.contains("* Alpha")), tail(ext.terminal()));
        }
        check("audit log records project activations", Files.readString(home.resolve("audit.log")).contains("PROJECT"), "");
    }

    // ================================================================== fixtures

    private static void writeCorpus(Path dir) throws IOException {
        Files.writeString(dir.resolve("kira.txt"), """
                KİRA SÖZLEŞMESİ
                Madde 4: Aylık kira bedeli 25.000 TL olup her ayın beşinde peşin ödenir.
                Madde 5: Kira artış oranı her yıl TÜFE on iki aylık ortalaması kadardır.
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("fatura.txt"), "Fatura No 2026-118. Hizmet bedeli 4.200 TL.",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("tablo.csv"), "Ad;Tutar;Tarih\nAyşe;1200;2026-01-05\nMehmet;950;2026-02-11\n",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("~$kilit.txt"), "office owner file", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("bozuk.pdf"), "%PDF-1.4 this is not really a pdf", StandardCharsets.UTF_8);
        Path nm = Files.createDirectories(dir.resolve("node_modules"));
        Files.writeString(nm.resolve("gizli.txt"), "gizli paket belgesi", StandardCharsets.UTF_8);
    }

    private static InvertedIndex localIndex() {
        InvertedIndex index = new InvertedIndex();
        Workbench wb = new Workbench(new DocumentParser(), index, null);
        try {
            Path tmp = Files.createTempFile("dwb-local-", ".txt");
            Files.writeString(tmp, "Kira sözleşmesi: kira artış oranı TÜFE ile sınırlıdır.", StandardCharsets.UTF_8);
            wb.ingest(tmp, "yerel-kira.txt");
            Files.delete(tmp);
        } catch (IOException | IngestionException e) {
            throw new IllegalStateException(e);
        }
        return index;
    }

    private static WebSearchBridge.Config webConfig() {
        return new WebSearchBridge.Config(WebSearchBridge.Provider.SEARXNG, URI.create("http://searx.intranet"), null,
                Duration.ofSeconds(2), 8, 512 << 10, 600, 4, 4_000, Duration.ofSeconds(60));
    }

    static final String SEARXNG_JSON = """
            {"results": [
              {"title": "2026 kira artış oranı açıklandı", "url": "https://example.org/kira-2026",
               "content": "Konut kira artış oranı TÜFE on iki aylık ortalamasına göre belirlendi."},
              {"title": "Süper Lig sonuçları", "url": "https://example.org/futbol",
               "content": "Hafta sonu oynanan futbol maçlarının sonuçları."},
              {"title": "Kiracılar için rehber", "url": "https://example.org/rehber",
               "content": "Kira sözleşmesinde artış maddesi nasıl yazılır?"},
              {"title": "Mercimek çorbası tarifi", "url": "https://example.org/yemek",
               "content": "Kırmızı mercimek, soğan ve havuç ile hazırlanır."},
              {"title": "Hava durumu", "url": "https://example.org/hava", "content": "Yarın parçalı bulutlu."}
            ]}
            """;

    static final String BRAVE_JSON = """
            {"web": {"results": [{"title": "<b>Kira</b>", "url": "https://example.org/k",
              "description": "<strong>Kira</strong> artış oranı &lt;%25&gt; &amp; TÜFE"}]}}
            """;

    static final class FakeTransport implements WebSearchBridge.Transport {
        final AtomicInteger calls = new AtomicInteger();
        final String body;
        volatile java.net.http.HttpRequest last;
        int status = 200;
        IOException failWith;

        FakeTransport(String body) {
            this.body = body;
        }

        @Override
        public Response send(java.net.http.HttpRequest request, int maxBodyBytes) throws IOException {
            calls.incrementAndGet();
            last = request;
            if (failWith != null) {
                throw failWith;
            }
            return new Response(status, body);
        }
    }

    static final class FakeModel implements AnswerModel {
        volatile String lastPrompt = "";
        volatile int calls;

        @Override
        public String modelId() {
            return "fake-model";
        }

        @Override
        public int contextCharBudget() {
            return 8_000;
        }

        @Override
        public AnswerStats stream(AnswerRequest request, TokenSink sink) {
            calls++;
            lastPrompt = request.userMessage();
            sink.accept("Kira artışı TÜFE ile sınırlıdır [W1] [S1].\nİkinci satır.");
            return new AnswerStats("fake-model", "STOP", request.userMessage().length() / 4, 14, 3, 9);
        }
    }

    static final class CountingIngestor implements DocumentIngestor {
        final DocumentParser parser = new DocumentParser();
        final AtomicInteger parses = new AtomicInteger();

        @Override
        public boolean supports(Path file) {
            return parser.supports(file);
        }

        @Override
        public IngestResult ingest(Path file, String displayName, Predicate<String> isKnown) throws IngestionException {
            IngestResult r = parser.ingest(file, displayName, isKnown);
            if (r instanceof IngestResult.Ingested) {
                parses.incrementAndGet();
            }
            return r;
        }
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static OsShellBridge.ProcessLauncher recorder(List<List<String>> sink) {
        return command -> {
            sink.add(List.copyOf(command));
            return new Process() {
                @Override
                public OutputStream getOutputStream() {
                    return OutputStream.nullOutputStream();
                }

                @Override
                public InputStream getInputStream() {
                    return InputStream.nullInputStream();
                }

                @Override
                public InputStream getErrorStream() {
                    return InputStream.nullInputStream();
                }

                @Override
                public int waitFor() {
                    return 1;
                }

                @Override
                public int exitValue() {
                    return 1;
                }

                @Override
                public void destroy() {
                }
            };
        };
    }

    private static void writeBytes(Path file, long size) throws IOException {
        byte[] block = "lorem ipsum dolor sit amet\n".repeat(2_500).getBytes(StandardCharsets.US_ASCII);
        try (OutputStream out = Files.newOutputStream(file)) {
            long left = size;
            while (left > 0) {
                int n = (int) Math.min(block.length, left);
                out.write(block, 0, n);
                left -= n;
            }
        }
    }

    // ================================================================== harness

    private void section(String title) {
        System.out.println();
        System.out.println("== " + title);
    }

    private void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  ok    " + name);
        } else {
            failures.add(name + (detail.isEmpty() ? "" : " — " + detail));
            System.out.println("  FAIL  " + name + (detail.isEmpty() ? "" : " — " + detail));
        }
    }

    private static String tail(InternalTerminalEngine t) {
        return String.join(" | ", t.buffer().tail(8));
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
