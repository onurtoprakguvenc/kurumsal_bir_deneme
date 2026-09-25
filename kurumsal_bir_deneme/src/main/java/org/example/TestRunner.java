package org.example;

import org.example.core.DocumentIngestor;
import org.example.core.IngestionException;
import org.example.core.Workbench;
import org.example.index.InvertedIndex;
import org.example.ingest.DocumentParser;
import org.example.input.KeyMapRegistry;
import org.example.input.KeyMapRegistry.Action;
import org.example.input.KeyMapRegistry.BindResult;
import org.example.input.KeyMapRegistry.KeyStroke;
import org.example.model.SearchHit;
import org.example.model.SearchResult;
import org.example.platform.OsShellBridge;
import org.example.platform.OsShellBridge.ShellResult;
import org.example.repl.InternalTerminalEngine;
import org.example.repl.InternalTerminalEngine.Outcome;
import org.example.state.PanelStateCoordinator;
import org.example.state.PanelStateCoordinator.Panel;
import org.example.state.Subscription;
import org.example.state.ViewModeCoordinator;
import org.example.state.ViewModeCoordinator.ViewMode;
import org.example.storage.IndexStorageEngine;
import org.example.workbench.WorkbenchController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Headless verification of the workbench shell: snapshot save/load and crash recovery, warm start without
 * re-parsing, terminal commands, view modes, panels, key bindings and OS actions through a recording process
 * launcher (nothing is actually opened). No JavaFX toolkit is started.
 *
 * <pre>
 * java -Xmx512m -cp build/classes/java/main:&lt;runtime deps&gt; org.example.TestRunner
 * gradle -q runTests
 * </pre>
 *
 * Exits with status 1 if any check fails.
 */
public final class TestRunner {

    private int passed;
    private final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        TestRunner runner = new TestRunner();
        Path root = Files.createTempDirectory("dwb-test-");
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
        Path docs = Files.createDirectories(root.resolve("docs dir"));
        Path data = root.resolve("data");
        writeCorpus(docs);

        persistenceAndWarmStart(docs, data);
        crashRecovery(root.resolve("crash"), docs);
        staleSources(docs, data);
        terminalEngine();
        viewModes();
        panels();
        keyBindings(root.resolve("keys"));
        shellBridge(docs);
        controllerActions(docs, root.resolve("actions"));
    }

    // ================================================================== sections

    private void persistenceAndWarmStart(Path docs, Path data) throws Exception {
        section("Persistence: save, then warm start without re-parsing");
        List<SearchHit> before;
        try (WorkbenchController a = controller(data, new CountingIngestor(), recorder(new ArrayList<>()))) {
            a.startup();
            Outcome added = a.terminal().execute("index --add \"" + docs + "\"");
            check("index --add succeeded", added.succeeded(), added.toString());
            check("three documents indexed", a.workbench().documents().size() == 3,
                    "got " + a.workbench().documents().size());
            before = a.search("kira bedeli", 10).hits();
            check("search finds the lease", !before.isEmpty() && before.getFirst().fileName().equals("kira.txt"),
                    before.toString());
            check("index is dirty before save", a.dirty(), "");
            Outcome saved = a.terminal().execute("index --save");
            check("index --save succeeded", saved.succeeded(), saved.toString());
            check("index is clean after save", !a.dirty(), "");
        }
        check("snapshot file written", Files.isRegularFile(data.resolve("index.dwb")), "");
        check("no temp file left behind", !Files.exists(data.resolve("index.dwb.tmp")), "");
        check("keybindings.properties created", Files.isRegularFile(data.resolve("keybindings.properties")), "");

        CountingIngestor counting = new CountingIngestor();
        try (WorkbenchController b = controller(data, counting, recorder(new ArrayList<>()))) {
            WorkbenchController.StartupReport report = b.startup();
            check("warm start restored 3 documents", report.warmed() == 3, "warmed " + report.warmed());
            check("loaded from primary file", report.load().origin() == IndexStorageEngine.Origin.PRIMARY,
                    report.load().origin().toString());
            check("no document was re-parsed", counting.parses.get() == 0, "parses " + counting.parses.get());
            List<SearchHit> after = b.search("kira bedeli", 10).hits();
            check("identical ranking after reload", sameHits(before, after), before + " vs " + after);
            check("warm index is not dirty", !b.dirty(), "");
            String sha = after.getFirst().docId();
            check("parse latency survived the snapshot",
                    b.view().detailed(0).parseMillis() >= 0, "sha " + sha);
            System.out.printf(java.util.Locale.ROOT, "  (snapshot %d bytes, read %.2f ms, index %.2f ms)%n", report.load().bytes(),
                    report.load().millis(), report.warmMillis());
        }
    }

    private void crashRecovery(Path dir, Path docs) throws Exception {
        section("Crash resistance: checksums, backup and temp recovery");
        Workbench wb = new Workbench(new DocumentParser(), new InvertedIndex(), null);
        try (Stream<Path> files = Files.list(docs)) {
            for (Path f : files.sorted().toList()) {
                wb.ingest(f, null);
            }
        }
        IndexStorageEngine engine = new IndexStorageEngine(dir.resolve("index.dwb"));
        Path main = dir.resolve("index.dwb");
        Path bak = dir.resolve("index.dwb.bak");
        Path tmp = dir.resolve("index.dwb.tmp");

        engine.save(wb.documents().subList(0, 2), d -> org.example.storage.MetadataRecord.of(d, 5));
        engine.save(wb.documents(), d -> org.example.storage.MetadataRecord.of(d, 5));
        check("second save keeps a backup", Files.isRegularFile(bak), "");
        check("clean load uses primary with 3 docs",
                engine.load().origin() == IndexStorageEngine.Origin.PRIMARY && engine.load().entries().size() == 3, "");

        byte[] bytes = Files.readAllBytes(main);
        bytes[bytes.length / 2] ^= 0x5A;
        Files.write(main, bytes);
        IndexStorageEngine.LoadReport flipped = engine.load();
        check("bit flip detected, backup served", flipped.origin() == IndexStorageEngine.Origin.BACKUP
                && flipped.entries().size() == 2 && !flipped.problems().isEmpty(), flipped.problems().toString());

        engine.save(wb.documents(), d -> org.example.storage.MetadataRecord.of(d, 5));
        Files.move(main, tmp, StandardCopyOption.REPLACE_EXISTING);
        IndexStorageEngine.LoadReport recovered = engine.load();
        check("crash between renames recovers the completed temp file",
                recovered.origin() == IndexStorageEngine.Origin.RECOVERED_TEMP && recovered.entries().size() == 3,
                recovered.origin() + " " + recovered.problems());

        byte[] full = Files.readAllBytes(tmp);
        Files.write(main, java.util.Arrays.copyOf(full, full.length - 7));
        Files.delete(bak);
        Files.delete(tmp);
        IndexStorageEngine.LoadReport torn = engine.load();
        check("torn write is rejected as a whole", torn.origin() == IndexStorageEngine.Origin.NONE
                && torn.entries().isEmpty() && !torn.problems().isEmpty(), torn.problems().toString());
    }

    private void staleSources(Path docs, Path data) throws Exception {
        section("Startup freshness: changed sources re-indexed, missing ones dropped");
        Path lease = docs.resolve("kira.txt");
        Files.writeString(lease, Files.readString(lease) + "\nEk madde: depozito iki aylık kira bedelidir.\n",
                StandardCharsets.UTF_8);
        Files.setLastModifiedTime(lease, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        Path extra = docs.resolve("gecici.txt");

        CountingIngestor counting = new CountingIngestor();
        try (WorkbenchController c = controller(data, counting, recorder(new ArrayList<>()))) {
            WorkbenchController.StartupReport report = c.startup();
            check("one stale source detected", report.stale() == 1, "stale " + report.stale());
            int reingested = report.reingest().get(30, TimeUnit.SECONDS);
            check("stale source re-ingested in background", reingested == 1 && counting.parses.get() == 1,
                    "reingested " + reingested + ", parses " + counting.parses.get());
            check("new text is searchable", !c.search("depozito", 5).isEmpty(), "");
            Files.writeString(extra, "Geçici not: silinecek belge.", StandardCharsets.UTF_8);
            c.terminal().execute("index --add \"" + extra + "\"");
            c.save();
        }
        Files.delete(extra);
        try (WorkbenchController d = controller(data, new CountingIngestor(), recorder(new ArrayList<>()))) {
            WorkbenchController.StartupReport report = d.startup();
            check("missing source dropped at startup", report.dropped() == 1 && report.warmed() == 3,
                    "dropped " + report.dropped() + ", warmed " + report.warmed());
            check("dropping makes the index dirty", d.dirty(), "");
        }
    }

    private void terminalEngine() {
        section("Terminal engine: parsing, dispatch, bounded streaming output");
        InternalTerminalEngine.LineBuffer small = new InternalTerminalEngine.LineBuffer(5, 32);
        try (InternalTerminalEngine t = new InternalTerminalEngine(small)) {
            List<String> streamed = new CopyOnWriteArrayList<>();
            Subscription sub = small.subscribe((seq, line) -> streamed.add(line));
            AtomicInteger seen = new AtomicInteger();
            t.register(new InternalTerminalEngine.CommandSpec("echo", "echo [--times N] <text>", "test", Set.of("times"),
                    (inv, out) -> {
                        seen.set(inv.args().size());
                        for (int i = 0; i < inv.intOption("times", 1, 1, 100); i++) {
                            out.println(inv.joinedArgs());
                        }
                    }));
            t.execute("echo --times 8 \"C:\\My Docs\\a b.pdf\" x");
            check("quoted Windows path kept as one argument", seen.get() == 2, "args " + seen.get());
            check("ring keeps only the newest 5 lines", small.size() == 5 && small.nextSequence() == 9,
                    small.size() + "/" + small.nextSequence());
            check("every line was streamed to listeners", streamed.size() == 9, "streamed " + streamed.size());
            t.execute("echo " + "y".repeat(100));
            check("overlong line truncated", small.tail(1).getFirst().length() == 32, small.tail(1).toString());
            Outcome unknown = t.execute("ehco hi");
            check("unknown command suggests the closest",
                    unknown instanceof Outcome.Unknown u && "echo".equals(u.suggestion()), unknown.toString());
            Outcome bad = t.execute("echo \"unterminated");
            check("unterminated quote is a clean failure", bad instanceof Outcome.Failed, bad.toString());
            t.execute("clear");
            check("clear empties the buffer", small.size() == 0, "size " + small.size());
            sub.close();
            t.execute("echo after");
            check("closed subscription receives nothing", streamed.size() == 15, "streamed " + streamed.size());
            check("history recorded", t.history().contains("clear"), t.history().toString());
            Outcome async = t.submit("echo async").join();
            check("submit runs on the terminal thread", async.succeeded(), async.toString());
        }
    }

    private void viewModes() {
        section("View modes: simple vs detailed without rebuilding results");
        ViewModeCoordinator view = new ViewModeCoordinator();
        List<String> events = new ArrayList<>();
        view.onModeChanged((prev, cur) -> events.add(prev + "->" + cur));
        SearchResult result = new SearchResult("kira", List.of("kira"), List.of(
                hit("a".repeat(64), "plan.xlsx [beta · 2026-09-20 19:07]", 8.0),
                hit("b".repeat(64), "kira.txt", 2.0)), 2, 1_000_000);
        view.publish(result);
        int identity = view.tableIdentity();
        ViewModeCoordinator.SimpleView top = view.simple(0);
        check("simple view strips fork tags", top.documentName().equals("plan.xlsx"), top.documentName());
        check("top hit is 100% / HIGH", top.relevancePercent() == 100
                && top.relevance() == ViewModeCoordinator.Relevance.HIGH, top.toString());
        check("second hit relative relevance 25% / LOW", view.simple(1).relevancePercent() == 25
                && view.simple(1).relevance() == ViewModeCoordinator.Relevance.LOW, view.simple(1).toString());
        view.toggle();
        view.setMode(ViewMode.DETAILED);
        check("toggle fired exactly once (no-op set ignored)", events.equals(List.of("SIMPLE->DETAILED")),
                events.toString());
        check("table not reallocated by toggling", view.tableIdentity() == identity, "");
        check("current projection is detailed", view.view(0) instanceof ViewModeCoordinator.DetailedView d
                && d.bm25() == 8.0 && d.coordinate().startsWith("p.2 #0"), view.view(0).toString());
        StringBuilder sb = new StringBuilder();
        view.render(0, sb);
        check("detailed render shows bm25", sb.toString().contains("bm25=8.000"), sb.toString());
        view.publish(new SearchResult("x", List.of("x"), List.of(hit("c".repeat(64), "c.txt", 1)), 1, 1));
        check("smaller result reuses the table", view.tableIdentity() == identity && view.size() == 1, "");
    }

    private void panels() {
        section("Panel state coordinator");
        PanelStateCoordinator p = new PanelStateCoordinator();
        AtomicInteger changes = new AtomicInteger();
        Subscription sub = p.onChange((prev, cur) -> changes.incrementAndGet());
        check("terminal starts hidden", !p.isShown(Panel.TERMINAL), "");
        p.toggle(Panel.TERMINAL);
        p.show(Panel.TERMINAL);
        check("redundant show emits no event", changes.get() == 1, "changes " + changes.get());
        p.hide(Panel.FILE_TREE);
        p.hide(Panel.RESULTS);
        boolean lastHidden = p.hide(Panel.TERMINAL);
        check("last docked panel cannot be hidden", !lastHidden && p.isShown(Panel.TERMINAL), "");
        check("detach works", p.detach(Panel.RESULTS) || p.state(Panel.RESULTS).visibility()
                == PanelStateCoordinator.Visibility.DETACHED, "");
        sub.close();
        check("subscription removed on close", p.listenerCount() == 0, "listeners " + p.listenerCount());
    }

    private void keyBindings(Path dir) throws IOException {
        section("Key binding registry");
        Path file = dir.resolve("keybindings.properties");
        Files.createDirectories(dir);
        Files.writeString(file, """
                # hand-edited
                FOCUS_SEARCH = ctrl + k
                TOGGLE_MODE = Ctrl+K
                SAVE_INDEX = none
                OPEN_SELECTED = Q
                SHOW_IN_EXPLORER = Ctrl+Wibble
                NOT_AN_ACTION = F1
                TOGGLE_TERMINAL = shift+ctrl+`
                """, StandardCharsets.UTF_8);
        KeyMapRegistry keys = new KeyMapRegistry(file);
        List<KeyMapRegistry.Issue> issues = keys.load();
        check("custom binding applied", keys.strokeFor(Action.FOCUS_SEARCH).map(Object::toString)
                .orElse("").equals("Ctrl+K"), keys.bindings().toString());
        check("conflicting entry falls back to its default", keys.strokeFor(Action.TOGGLE_MODE)
                .map(Object::toString).orElse("").equals("Ctrl+M"), keys.bindings().toString());
        check("explicit none unbinds", keys.strokeFor(Action.SAVE_INDEX).isEmpty(), "");
        check("typing-stealing stroke rejected", keys.strokeFor(Action.OPEN_SELECTED).map(Object::toString)
                .orElse("").equals("Enter"), "");
        check("aliases and modifier order normalized", keys.strokeFor(Action.TOGGLE_TERMINAL).map(Object::toString)
                .orElse("").equals("Ctrl+Shift+Back_Quote"), keys.strokeFor(Action.TOGGLE_TERMINAL).toString());
        check("four issues reported", issues.size() == 4, issues.toString());

        BindResult conflict = keys.bind(Action.TOGGLE_MODE, "Ctrl+K", false);
        check("bind reports conflict", conflict instanceof BindResult.Conflict c && c.holder() == Action.FOCUS_SEARCH,
                conflict.toString());
        BindResult forced = keys.bind(Action.TOGGLE_MODE, "Ctrl+K", true);
        check("forced bind displaces holder to its default", forced instanceof BindResult.Bound b
                && b.displaced().orElse(null) == Action.FOCUS_SEARCH
                && keys.strokeFor(Action.FOCUS_SEARCH).map(Object::toString).orElse("").equals("Ctrl+F"),
                keys.bindings().toString());
        check("reverse lookup is consistent", keys.actionFor(KeyStroke.parse("ctrl+k", true).orElseThrow())
                .orElse(null) == Action.TOGGLE_MODE, "");
        check("invalid stroke rejected", keys.bind(Action.CLEAR_TERMINAL, "Hyper+X", false)
                instanceof BindResult.Invalid, "");
        keys.save();
        KeyMapRegistry reloaded = new KeyMapRegistry(file);
        check("save/load round-trip without issues", reloaded.load().isEmpty()
                && reloaded.bindings().equals(keys.bindings()), reloaded.bindings() + " vs " + keys.bindings());
        check("unchanged file is not reloaded", reloaded.reloadIfChanged().isEmpty(), "");
        Files.writeString(file, "CLEAR_TERMINAL = F9\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 10_000));
        check("hot reload picks up external edit", reloaded.reloadIfChanged().isPresent()
                && reloaded.strokeFor(Action.CLEAR_TERMINAL).map(Object::toString).orElse("").equals("F9"), "");
    }

    private void shellBridge(Path docs) throws Exception {
        section("OS shell bridge (recording launcher, nothing is launched)");
        List<List<String>> recorded = new CopyOnWriteArrayList<>();
        Path lease = docs.resolve("kira.txt").toAbsolutePath();
        try (OsShellBridge win = new OsShellBridge(OsShellBridge.Platform.WINDOWS, recorder(recorded), false);
             OsShellBridge mac = new OsShellBridge(OsShellBridge.Platform.MAC, recorder(recorded), false)) {
            ShellResult open = win.open(lease).get(5, TimeUnit.SECONDS);
            check("windows open via explorer.exe", open.ok()
                    && recorded.getLast().equals(List.of("explorer.exe", lease.toString())), recorded.toString());
            win.reveal(lease).get(5, TimeUnit.SECONDS);
            check("windows reveal keeps /select, and a spaced path as separate args",
                    recorded.getLast().equals(List.of("explorer.exe", "/select,", lease.toString())),
                    recorded.getLast().toString());
            mac.reveal(lease).get(5, TimeUnit.SECONDS);
            check("mac reveal uses open -R", recorded.getLast().equals(List.of("open", "-R", lease.toString())), "");
            int count = recorded.size();
            Path script = Files.writeString(docs.getParent().resolve("evil.bat"), "@echo off");
            ShellResult refused = win.open(script).get(5, TimeUnit.SECONDS);
            check("executables are never opened", refused instanceof ShellResult.Rejected
                    && recorded.size() == count, refused.describe());
            ShellResult missing = win.open(docs.resolve("nope.pdf")).get(5, TimeUnit.SECONDS);
            check("missing file rejected", missing instanceof ShellResult.Rejected, missing.describe());
            OsShellBridge failing = new OsShellBridge(OsShellBridge.Platform.LINUX, cmd -> {
                throw new IOException("xdg-open not installed");
            }, false);
            ShellResult failed = failing.open(lease).get(5, TimeUnit.SECONDS);
            check("launch failure reported, not thrown", failed instanceof ShellResult.Failed, failed.describe());
            failing.close();
        }
    }

    private void controllerActions(Path docs, Path data) throws Exception {
        section("Workbench controller: REPL commands, key dispatch, file actions");
        List<List<String>> recorded = new CopyOnWriteArrayList<>();
        try (WorkbenchController c = controller(data, new CountingIngestor(), recorder(recorded))) {
            c.startup();
            AtomicInteger focus = new AtomicInteger();
            c.setUiHooks(new WorkbenchController.UiHooks() {
                @Override
                public void focusSearch() {
                    focus.incrementAndGet();
                }
            });
            InternalTerminalEngine t = c.terminal();
            t.execute("index --add \"" + docs + "\"");
            Outcome search = t.execute("search --top 5 fatura");
            check("search command succeeded", search.succeeded() && c.view().size() >= 1, search.toString());
            check("simple output shows a relevance badge",
                    t.buffer().snapshot().stream().anyMatch(l -> l.contains("[High 100%]")), tail(t));
            t.execute("mode --toggle");
            check("mode --toggle switched to detailed", c.view().mode() == ViewMode.DETAILED, "");
            t.execute("search fatura");
            check("detailed output shows telemetry", t.buffer().snapshot().stream()
                    .anyMatch(l -> l.contains("bm25=") && l.contains("tf={fatura=")), tail(t));
            t.execute("stats --memory");
            check("stats --memory prints heap", t.buffer().tail(3).stream().anyMatch(l -> l.contains("heap")), tail(t));
            t.execute("stats --index");
            check("stats --index prints counts", t.buffer().tail(3).stream()
                    .anyMatch(l -> l.startsWith("index: 3 documents")), tail(t));

            t.execute("open 1");
            check("open 1 launches the first hit", !recorded.isEmpty()
                    && recorded.getLast().getLast().endsWith("fatura.txt"), recorded.toString());
            t.execute("explore 1");
            check("explore 1 reveals it", recorded.getLast().contains("/select,"), recorded.getLast().toString());

            c.dispatch(KeyStroke.parse("Ctrl+F", true).orElseThrow());
            check("Ctrl+F reaches the UI hook", focus.get() == 1, "");
            c.dispatch(KeyStroke.parse("Ctrl+J", true).orElseThrow());
            check("Ctrl+J toggles the terminal panel", c.panels().isShown(Panel.TERMINAL), "");
            int before = recorded.size();
            c.dispatch(KeyStroke.parse("Enter", false).orElseThrow());
            waitFor(() -> recorded.size() > before);
            check("Enter opens the selected row", recorded.size() == before + 1, recorded.toString());
            boolean unbound = c.dispatch(KeyStroke.parse("Ctrl+Alt+F12", true).orElseThrow());
            check("unbound stroke is not consumed", !unbound, "");

            Outcome rebind = t.execute("set-key TOGGLE_MODE Ctrl+F");
            check("set-key conflict is refused", rebind instanceof Outcome.Failed, rebind.toString());
            t.execute("set-key TOGGLE_MODE Ctrl+Shift+M");
            c.dispatch(KeyStroke.parse("Ctrl+Shift+M", true).orElseThrow());
            check("rebound shortcut works immediately", c.view().mode() == ViewMode.SIMPLE, "");
            check("rebinding persisted", Files.readString(data.resolve("keybindings.properties"))
                    .contains("TOGGLE_MODE = Ctrl+Shift+M"), "");

            t.execute("index --rebuild");
            check("rebuild re-ingested all documents", c.workbench().documents().size() == 3, tail(t));
            t.execute("clear");
            check("clear empties the terminal", t.buffer().size() == 0, "");
        }
        check("dirty index saved on close", Files.isRegularFile(data.resolve("index.dwb")), "");
    }

    // ================================================================== fixtures

    private static void writeCorpus(Path dir) throws IOException {
        Files.writeString(dir.resolve("kira.txt"), """
                KİRA SÖZLEŞMESİ
                Madde 4: Aylık kira bedeli 25.000 TL olup her ayın beşinde peşin ödenir.
                Madde 7: Kiracı, kira bedelini geciktirirse aylık yüzde iki gecikme faizi uygulanır.
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("fatura.txt"), """
                Fatura No: 2026-118
                Hizmet bedeli: 4.200 TL. Fatura tarihi 12.09.2026, son ödeme 30 gün içindedir.
                Fatura itirazları yedi gün içinde yazılı yapılır.
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("toplanti.txt"), """
                Toplantı notları: bütçe revizyonu ve depo taşınması görüşüldü.
                Karar: yeni tedarikçi ile görüşmeler sürdürülecek.
                """, StandardCharsets.UTF_8);
    }

    private static WorkbenchController controller(Path data, DocumentIngestor ingestor,
                                                  OsShellBridge.ProcessLauncher launcher) {
        Workbench workbench = new Workbench(ingestor, new InvertedIndex(), null);
        OsShellBridge shell = new OsShellBridge(OsShellBridge.Platform.WINDOWS, launcher, false);
        return new WorkbenchController(workbench, WorkbenchController.Config.defaults(data), shell);
    }

    /** Wraps the real parser and counts full extractions (duplicates are not counted). */
    private static final class CountingIngestor implements DocumentIngestor {
        final DocumentParser parser = new DocumentParser();
        final AtomicInteger parses = new AtomicInteger();

        @Override
        public boolean supports(Path file) {
            return parser.supports(file);
        }

        @Override
        public IngestResult ingest(Path file, String displayName, Predicate<String> isKnown)
                throws IngestionException {
            IngestResult result = parser.ingest(file, displayName, isKnown);
            if (result instanceof IngestResult.Ingested) {
                parses.incrementAndGet();
            }
            return result;
        }
    }

    private static OsShellBridge.ProcessLauncher recorder(List<List<String>> sink) {
        return command -> {
            sink.add(List.copyOf(command));
            return new FinishedProcess(1);
        };
    }

    /** A process that has already exited; stands in for explorer.exe and friends. */
    private static final class FinishedProcess extends Process {
        private final int exit;

        FinishedProcess(int exit) {
            this.exit = exit;
        }

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
            return exit;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public int exitValue() {
            return exit;
        }

        @Override
        public void destroy() {
        }
    }

    private static SearchHit hit(String docId, String name, double score) {
        return new SearchHit(docId, name, 0, 2, score, "… snippet …", 10, false, false,
                List.of(new org.example.model.Location(docId, 0, 3, 42, 4)));
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

    private static boolean sameHits(List<SearchHit> a, List<SearchHit> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).docId().equals(b.get(i).docId()) || a.get(i).chunkIndex() != b.get(i).chunkIndex()
                    || Math.abs(a.get(i).score() - b.get(i).score()) > 1e-9) {
                return false;
            }
        }
        return true;
    }

    private static String tail(InternalTerminalEngine t) {
        return String.join(" | ", t.buffer().tail(6));
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
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
