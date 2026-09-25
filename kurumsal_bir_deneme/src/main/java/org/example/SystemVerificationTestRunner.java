package org.example;

import org.example.ExtendedTestRunner.CountingIngestor;
import org.example.ExtendedTestRunner.FakeModel;
import org.example.ExtendedTestRunner.FakeTransport;
import org.example.admin.AdminControlEngine;
import org.example.admin.AuditLog;
import org.example.core.Workbench;
import org.example.index.InvertedIndex;
import org.example.ingest.DocumentParser;
import org.example.input.KeyMapRegistry;
import org.example.input.KeyMapRegistry.Action;
import org.example.input.KeyMapRegistry.BindResult;
import org.example.model.DocumentRecord;
import org.example.model.DocumentType;
import org.example.net.WebSearchBridge;
import org.example.net.WebSearchBridge.HybridOutcome;
import org.example.platform.OsShellBridge;
import org.example.platform.OsShellBridge.ShellResult;
import org.example.preview.PreviewResult;
import org.example.preview.PreviewService;
import org.example.project.ProjectWorkspaceManager.SwitchReport;
import org.example.quota.QuotaManager;
import org.example.repl.InternalTerminalEngine;
import org.example.repl.InternalTerminalEngine.CommandSpec;
import org.example.repl.InternalTerminalEngine.Outcome;
import org.example.state.FocusModeCoordinator;
import org.example.state.PanelStateCoordinator.Panel;
import org.example.state.ViewModeCoordinator.MemorySnapshot;
import org.example.state.ViewModeCoordinator.ViewMode;
import org.example.ui.CommandBarView.Mode;
import org.example.workbench.ExtendedWorkbenchController;
import org.example.workbench.WorkbenchController;
import org.example.workbench.WorkbenchController.RemovalResult;
import org.example.workbench.WorkbenchController.WorkbenchEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Headless system verification of the UI action pipelines and a regression sweep over all ten workbench
 * specifications, run under the 512 MB heap ceiling. No JavaFX toolkit is started: every action the chassis offers
 * (context menus, hover buttons, toolbar, dialogs) ends in a controller / registry method, and those are what this
 * runner drives. The system clipboard is replaced by a recorder, the OS shell by a recording launcher, the web by a
 * fake transport — nothing is opened, nothing leaves the machine. Files deleted here live in a temp directory.
 *
 * <pre>gradle -q runSystemTests</pre>
 */
public final class SystemVerificationTestRunner {

    private static final long HEAP_LIMIT = 512L << 20;

    private int passed;
    private final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        SystemVerificationTestRunner runner = new SystemVerificationTestRunner();
        Path root = Files.createTempDirectory("dwb-sys-");
        try {
            runner.run(root);
        } finally {
            deleteRecursively(root);
        }
        System.out.println();
        System.out.printf("%d passed, %d failed%n", runner.passed, runner.failures.size());
        runner.failures.forEach(f -> System.out.println("  FAILED: " + f));
        System.out.println("jvm: " + MemorySnapshot.now().describe() + ", peak heap " + (peakHeapBytes() >> 20) + " MB");
        System.exit(runner.failures.isEmpty() ? 0 : 1);
    }

    private void run(Path root) throws Exception {
        heapCeiling();
        deletionPipeline(root.resolve("delete"));
        clipboardRoutines(root.resolve("clip"));
        contextActions(root.resolve("ctx"));
        commandBarModes();
        specRegression(root.resolve("specs"));
        projectsEndToEnd(root.resolve("home"), root.resolve("proj-docs"));
        heapUnderLoad(root.resolve("load"));
    }

    // ================================================================== heap ceiling

    private void heapCeiling() {
        section("Heap ceiling (-Xmx512m)");
        long max = Runtime.getRuntime().maxMemory();
        check("JVM max heap is at most 512 MB", max <= HEAP_LIMIT + (HEAP_LIMIT / 50),
                (max >> 20) + " MB — run via 'gradle runSystemTests' (maxHeapSize = 512m)");
    }

    // ================================================================== deletion pipeline

    private void deletionPipeline(Path dir) throws Exception {
        section("Deletion pipeline: untrack, purge, safety checks, persistence, audit");
        Path docs = Files.createDirectories(dir.resolve("docs"));
        Path data = dir.resolve("data");
        Path a = write(docs.resolve("alfa.txt"), "Alfa raporu: bütçe onayı ve harcama kalemleri.");
        Path b = write(docs.resolve("beta.txt"), "Beta sözleşmesi: teslimat ve ceza koşulları.");
        write(docs.resolve("gama.txt"), "Gama tutanağı: toplantı kararları ve sorumlular.");

        AuditLog audit = new AuditLog(dir.resolve("audit.log"));
        AdminControlEngine admin = new AdminControlEngine(dir.resolve("admin.properties"), audit, Clock.systemUTC(), null);
        admin.load();
        List<WorkbenchEvent> events = new CopyOnWriteArrayList<>();
        try (WorkbenchController c = controller(data, new ArrayList<>())) {
            c.onEvent(events::add);
            c.onEvent(admin.eventRecorder());
            check("3 documents ingested", c.addPath(docs, c.terminal().output()) == 3, "");

            DocumentRecord alfa = byName(c, "alfa.txt");
            RemovalResult untracked = c.untrack(alfa.sha256());
            check("untrack succeeds", untracked instanceof RemovalResult.Untracked, untracked.describe());
            check("untrack leaves the file on disk", Files.isRegularFile(a), "");
            check("untracked document leaves index and search", c.document(alfa.sha256()).isEmpty()
                    && c.search("bütçe", 5).isEmpty(), "");
            check("Removed(fromDisk=false) event emitted", events.stream().anyMatch(e ->
                    e instanceof WorkbenchEvent.Removed(DocumentRecord d, boolean disk) && !disk
                            && d.sha256().equals(alfa.sha256())), events.toString());
            check("index is dirty after untrack", c.dirty(), "");
            check("second untrack is rejected, nothing changes",
                    c.untrack(alfa.sha256()) instanceof RemovalResult.Rejected, "");

            DocumentRecord beta = byName(c, "beta.txt");
            check("purge preflight admits an unchanged local file", c.purgeRefusal(beta).isEmpty(),
                    c.purgeRefusal(beta).orElse(""));
            RemovalResult purged = c.purge(beta.sha256());
            check("purge deletes the file", purged instanceof RemovalResult.Purged && !Files.exists(b),
                    purged.describe());
            check("purged document leaves the index", c.document(beta.sha256()).isEmpty()
                    && c.search("teslimat", 5).isEmpty(), "");
            check("Removed(fromDisk=true) event emitted", events.stream().anyMatch(e ->
                    e instanceof WorkbenchEvent.Removed(DocumentRecord d, boolean disk) && disk), "");

            // --- safety checks: every refusal leaves disk and index untouched
            Path edited = write(docs.resolve("degisen.txt"), "Değişen belge: ilk sürüm metni burada.");
            c.ingest(edited);
            DocumentRecord editedDoc = byName(c, "degisen.txt");
            Files.writeString(edited, "Değişen belge: İKİNCİ sürüm metni burada.", StandardCharsets.UTF_8);
            RemovalResult changed = c.purge(editedDoc.sha256());
            check("file edited after indexing is NOT deleted", changed instanceof RemovalResult.Rejected r
                    && r.reason().contains("değişmiş") && Files.exists(edited)
                    && c.document(editedDoc.sha256()).isPresent(), changed.describe());

            Path gone = write(docs.resolve("kayip.txt"), "Kayıp belge metni.");
            c.ingest(gone);
            DocumentRecord goneDoc = byName(c, "kayip.txt");
            Files.delete(gone);
            check("missing file: purge refused, untrack offered", c.purgeRefusal(goneDoc).isPresent()
                    && c.purge(goneDoc.sha256()) instanceof RemovalResult.Rejected
                    && c.untrack(goneDoc.sha256()).ok(), "");

            Path internal = write(data.resolve("ic-dosya.txt"), "Çalışma alanı içindeki dosya.");
            c.ingest(internal);
            RemovalResult inData = c.purge(byName(c, "ic-dosya.txt").sha256());
            check("files inside the workspace data dir are never deleted",
                    inData instanceof RemovalResult.Rejected && Files.exists(internal), inData.describe());

            Path peerFile = write(dir.resolve("peer-cache.txt"), "Eşten gelen belge içeriği.");
            c.workbench().ingest(peerFile, "uzak.txt", "beta-node");
            DocumentRecord peer = byName(c, "uzak.txt");
            check("peer documents cannot be purged", c.purge(peer.sha256()) instanceof RemovalResult.Rejected
                    && Files.exists(peerFile), "");

            check("unknown document id is rejected", c.purge("0".repeat(64)) instanceof RemovalResult.Rejected
                    && c.untrack("0".repeat(64)) instanceof RemovalResult.Rejected, "");

            Path target = write(dir.resolve("hedef.txt"), "Sembolik bağlantının hedefi.");
            Path link = docs.resolve("baglanti.txt");
            boolean linked;
            try {
                Files.createSymbolicLink(link, target);
                linked = true;
            } catch (IOException | UnsupportedOperationException | SecurityException e) {
                linked = false;
                System.out.println("  skip  symbolic link check (" + e.getClass().getSimpleName() + ")");
            }
            if (linked) {
                c.ingest(link);
                DocumentRecord viaLink = byName(c, "baglanti.txt");
                check("symbolic links are never purged", c.purge(viaLink.sha256()) instanceof RemovalResult.Rejected
                        && Files.exists(target) && Files.exists(link), "");
            }

            c.save();
        }
        try (WorkbenchController reopened = controller(data, new ArrayList<>())) {
            reopened.startup();
            check("untrack/purge persist across restart (snapshot)", reopened.workbench().documents().stream()
                    .noneMatch(d -> d.fileName().equals("alfa.txt") || d.fileName().equals("beta.txt"))
                    && reopened.workbench().documents().stream().anyMatch(d -> d.fileName().equals("gama.txt")),
                    names(reopened));
        }
        List<String> tail = audit.tail(50);
        check("audit.log records UNTRACK and DELETE", tail.stream().anyMatch(l -> l.contains("UNTRACK"))
                && tail.stream().anyMatch(l -> l.contains("DELETE") && l.contains("beta.txt")), String.join("\n", tail));
        admin.close();
    }

    // ================================================================== clipboard

    private void clipboardRoutines(Path dir) throws Exception {
        section("Clipboard routines: copy path, copy extracted content");
        Path docs = Files.createDirectories(dir.resolve("docs"));
        StringBuilder big = new StringBuilder();
        int markers = 4_000;
        for (int i = 0; i < markers; i++) {
            big.append(String.format(Locale.ROOT, "İŞARET-%05d satırı: sözleşme maddesi ve açıklaması.%n", i));
        }
        Path file = write(docs.resolve("uzun belge.txt"), big.toString());
        List<String> clipboard = new ArrayList<>();
        try (WorkbenchController c = controller(dir.resolve("data"), new ArrayList<>())) {
            c.ingest(file);
            DocumentRecord d = byName(c, "uzun belge.txt");
            String path = c.copyPath(d, clipboard::add);
            check("copy path puts the absolute, normalized path", clipboard.size() == 1
                    && path.equals(file.toAbsolutePath().normalize().toString()) && clipboard.getFirst().equals(path),
                    clipboard.toString());
            int chars = c.copyContent(d, clipboard::add);
            String content = clipboard.getLast();
            check("document spans several chunks", d.chunks().size() > 1, d.chunks().size() + " chunks");
            check("copied content is the whole normalized text", chars == content.length()
                    && content.length() == d.charCount(), chars + " vs " + d.charCount());
            Matcher m = Pattern.compile("İŞARET-\\d{5}").matcher(content);
            int found = 0;
            while (m.find()) {
                found++;
            }
            check("every line copied exactly once (no chunk overlap or loss)", found == markers, found + "/" + markers);
            check("content copy is capped", WorkbenchController.contentText(d, 1_000).length() == 1_000, "");
        }
    }

    // ================================================================== context actions

    private void contextActions(Path dir) throws Exception {
        section("Context actions: open / reveal / row resolution, non-blocking launch");
        Path docs = Files.createDirectories(dir.resolve("docs with space"));
        Path doc = write(docs.resolve("kira.txt"), "Kira sözleşmesi: aylık kira bedeli ve artış oranı.");
        Path exe = write(docs.resolve("setup.exe"), "MZ");
        List<List<String>> launched = new CopyOnWriteArrayList<>();
        try (WorkbenchController c = controller(dir.resolve("data"), launched)) {
            List<WorkbenchEvent> events = new CopyOnWriteArrayList<>();
            c.onEvent(events::add);
            c.ingest(doc);
            c.search("kira", 10);
            check("row 0 resolves to its document", c.documentAt(0).map(DocumentRecord::fileName).orElse("")
                    .equals("kira.txt"), "");
            check("missing row resolves to nothing", c.documentAt(42).isEmpty() && c.documentAt(-1).isEmpty(), "");
            ShellResult open = c.openRow(0).get(5, TimeUnit.SECONDS);
            ShellResult reveal = c.revealRow(0).get(5, TimeUnit.SECONDS);
            String abs = doc.toAbsolutePath().normalize().toString();
            check("Dosyayı Aç → explorer.exe <path>", open.ok()
                    && launched.contains(List.of("explorer.exe", abs)), launched.toString());
            check("Klasörde Göster → explorer.exe /select, <path>", reveal.ok()
                    && launched.contains(List.of("explorer.exe", "/select,", abs)), launched.toString());
            ShellResult refused = c.openPath(exe).get(5, TimeUnit.SECONDS);
            check("executables are never opened", refused instanceof ShellResult.Rejected, refused.describe());
            check("every OS action is observable (audit hook)", events.stream()
                    .filter(e -> e instanceof WorkbenchEvent.FileAction).count() == 3, events.toString());
        }

        OsShellBridge slow = new OsShellBridge(OsShellBridge.Platform.WINDOWS, command -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return fakeProcess();
        }, false);
        try (WorkbenchController c = new WorkbenchController(new Workbench(new DocumentParser(), new InvertedIndex(), null),
                WorkbenchController.Config.defaults(dir.resolve("data2")), slow)) {
            long t0 = System.nanoTime();
            CompletableFuture<ShellResult> future = c.openPath(doc);
            double ms = (System.nanoTime() - t0) / 1e6;
            check("open returns immediately while the launcher is slow (UI never blocks)", ms < 100 && !future.isDone(),
                    String.format(Locale.ROOT, "%.1f ms", ms));
            check("…and completes in the background", future.get(5, TimeUnit.SECONDS).ok(), "");
        }
    }

    // ================================================================== command bar

    private void commandBarModes() {
        section("Command bar: clickable mode picker rewrites the prefix");
        check("search → ask", Mode.rewrite("kira artışı", Mode.ASK).equals("? kira artışı"), "");
        check("ask → web", Mode.rewrite("?  kira artışı", Mode.WEB).equals("@web kira artışı"),
                Mode.rewrite("?  kira artışı", Mode.WEB));
        check("web → command", Mode.rewrite("@web stats", Mode.COMMAND).equals("> stats"), "");
        check("command → search", Mode.rewrite("> stats", Mode.SEARCH).equals("stats"), "");
        boolean roundTrip = true;
        for (Mode m : Mode.values()) {
            roundTrip &= Mode.of(Mode.rewrite("kira", m)) == m;
        }
        check("every picked mode routes back to itself", roundTrip, "");
        check("chip labels shown to the user", Mode.SEARCH.label().equals("BM25") && Mode.WEB.label().equals("@WEB")
                && Mode.ASK.label().startsWith("ASK") && Mode.COMMAND.label().startsWith("CMD"), "");
    }

    // ================================================================== specs 1–9

    private void specRegression(Path dir) throws Exception {
        section("Specification regression 1–9");
        Path docs = Files.createDirectories(dir.resolve("docs"));
        writeCorpus(docs);
        Path data = dir.resolve("data");
        try (WorkbenchController c = controller(data, new ArrayList<>())) {
            c.startup();
            c.addPath(docs, c.terminal().output());

            // [1] persistence
            c.save();
            check("[1] index.dwb written on manual save", Files.isRegularFile(data.resolve("index.dwb")) && !c.dirty(), "");

            // [3] telemetry modes
            c.search("kira", 10);
            c.view().setMode(ViewMode.DETAILED);
            boolean detailed = c.view().size() > 0 && c.view().detailed(0).bm25() > 0
                    && !c.view().detailed(0).termFrequencies().isEmpty();
            c.view().toggle();
            check("[3] detailed view exposes BM25 and tf; toggle returns to simple", detailed
                    && c.view().mode() == ViewMode.SIMPLE && c.view().simple(0).relevancePercent() <= 100, "");

            // [4] terminal runs off the caller thread and toggles
            c.terminal().register(new CommandSpec("slow", "slow", "sleeps", Set.of(), (inv, out) -> {
                Thread.sleep(300);
                out.println("slow done");
            }));
            long t0 = System.nanoTime();
            CompletableFuture<Outcome> slow = c.terminal().submit("slow");
            double ms = (System.nanoTime() - t0) / 1e6;
            check("[4] terminal submit never blocks the caller", ms < 100 && !slow.isDone(),
                    String.format(Locale.ROOT, "%.1f ms", ms));
            check("[4] …and the command completes", slow.get(5, TimeUnit.SECONDS).succeeded(), "");
            boolean shown = c.panels().isShown(Panel.TERMINAL);
            c.perform(Action.TOGGLE_TERMINAL);
            check("[4] terminal toggles (button / shortcut path)", c.panels().isShown(Panel.TERMINAL) != shown, "");

            // [5] shortcut manager
            KeyMapRegistry keys = c.keys();
            Map<Action, String> swap = new EnumMap<>(Action.class);
            swap.put(Action.TOGGLE_MODE, "Ctrl+B");
            swap.put(Action.TOGGLE_FILE_TREE, "Ctrl+M");
            List<BindResult> swapped = keys.bindAll(swap);
            check("[5] shortcut manager swaps two bindings", swapped.stream().allMatch(r -> r instanceof BindResult.Bound)
                    && stroke(keys, Action.TOGGLE_MODE).equals("Ctrl+B")
                    && stroke(keys, Action.TOGGLE_FILE_TREE).equals("Ctrl+M"), swapped.toString());
            List<BindResult> clash = keys.bindAll(Map.of(Action.FOCUS_SEARCH, "Ctrl+J"));
            check("[5] a clash is reported and the old stroke kept", clash.size() == 1
                    && clash.getFirst() instanceof BindResult.Conflict
                    && stroke(keys, Action.FOCUS_SEARCH).equals("Ctrl+F")
                    && stroke(keys, Action.TOGGLE_TERMINAL).equals("Ctrl+J"), clash.toString());
            check("[5] unsafe strokes are refused", keys.bindAll(Map.of(Action.SAVE_INDEX, "A")).getFirst()
                    instanceof BindResult.Invalid && stroke(keys, Action.SAVE_INDEX).equals("Ctrl+S"), "");
            keys.save();
            Path file = keys.file();
            Files.writeString(file, Files.readString(file).replace("TOGGLE_TERMINAL = Ctrl+J",
                    "TOGGLE_TERMINAL = Ctrl+Shift+J"), StandardCharsets.UTF_8);
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
            check("[5] external edit hot-reloads", keys.reloadIfChanged().isPresent()
                    && stroke(keys, Action.TOGGLE_TERMINAL).equals("Ctrl+Shift+J"), stroke(keys, Action.TOGGLE_TERMINAL));

            // [6] preview limits + unload
            PreviewService.Limits limits = PreviewService.Limits.defaults();
            check("[6] preflight limits: 10 MB Office, 20 MB PDF, 5 MB TXT/CSV",
                    limits.limitFor(DocumentType.DOCX) == 10L << 20 && limits.limitFor(DocumentType.XLSX) == 10L << 20
                            && limits.limitFor(DocumentType.PPTX) == 10L << 20
                            && limits.limitFor(DocumentType.PDF) == 20L << 20
                            && limits.limitFor(DocumentType.TXT) == 5L << 20
                            && limits.limitFor(DocumentType.CSV) == 5L << 20, "");
            try (PreviewService preview = new PreviewService()) {
                Path huge = dir.resolve("huge.csv");
                writeBytes(huge, (5L << 20) + 1);
                check("[6] oversized CSV refused by preflight (no parsing)",
                        preview.preflight(huge).orElse(null) instanceof PreviewResult.TooLarge, "");
                PreviewResult ready = preview.preview(docs.resolve("kira.txt"));
                boolean held = ready instanceof PreviewResult.Ready && preview.retainedChars() > 0;
                preview.dismiss();
                check("[6] dismissing the dock releases the preview", held && preview.retainedChars() == 0
                        && preview.active().isEmpty(), "");
            }

            // [7] focus mode
            c.panels().show(Panel.TERMINAL);
            FocusModeCoordinator focus = new FocusModeCoordinator(c.panels(), c.view());
            focus.enter();
            boolean zen = !c.panels().isShown(Panel.FILE_TREE) && !c.panels().isShown(Panel.TERMINAL)
                    && c.panels().isShown(Panel.RESULTS);
            focus.exit();
            check("[7] one toggle collapses to search + results and restores",
                    zen && c.panels().isShown(Panel.FILE_TREE) && c.panels().isShown(Panel.TERMINAL), "");

            // [2] covered in contextActions; [1] shutdown save below
            c.untrack(byName(c, "fatura.txt").sha256());
        }
        try (WorkbenchController reopened = controller(data, new ArrayList<>())) {
            reopened.startup();
            check("[1] clean shutdown saved the dirty index", reopened.workbench().documents().stream()
                    .noneMatch(d -> d.fileName().equals("fatura.txt")) && !reopened.workbench().documents().isEmpty(),
                    names(reopened));
        }

        // [8] admin diagnostics
        AdminControlEngine admin = new AdminControlEngine(dir.resolve("admin.properties"),
                new AuditLog(dir.resolve("audit.log")), Clock.systemUTC(), null);
        admin.load();
        double ceiling = admin.settings().memoryCeiling();
        boolean rejected;
        try {
            admin.setMemoryCeiling(0.99);
            rejected = false;
        } catch (IllegalArgumentException e) {
            rejected = true;
        }
        check("[8] exclusions, heap ceiling range and audit log available", admin.excluded(
                Path.of("C:/p/node_modules/x.txt")) && ceiling >= 0.5 && ceiling <= 0.95 && rejected
                && admin.audit().file().getFileName().toString().equals("audit.log"), "");
        admin.close();

        // [9] web: graceful offline fallback and explicit routing
        InvertedIndex local = new InvertedIndex();
        Workbench wb = new Workbench(new DocumentParser(), local, null);
        wb.ingest(docs.resolve("kira.txt"), null);
        FakeTransport offline = new FakeTransport(null);
        offline.failWith = new ConnectException("Connection refused");
        QuotaManager quota = new QuotaManager(QuotaManager.Config.defaults());
        WebSearchBridge bridge = new WebSearchBridge(webConfig(), offline, () -> local, quota,
                () -> Optional.of(new FakeModel()), Clock.systemUTC());
        HybridOutcome o = bridge.ask("@web kira artış", d -> { });
        check("[9] @web offline → local BM25 results, no crash", o instanceof HybridOutcome.LocalOnly l
                && l.reason() == WebSearchBridge.Downgrade.OFFLINE && !l.local().isEmpty(), o.toString());
        check("[9] only an explicit @web prefix routes to the web", Mode.of("@web kira") == Mode.WEB
                && Mode.of("kira @web") == Mode.SEARCH, "");
        bridge.close();
    }

    // ================================================================== spec 10 + purge via the extended controller

    private void projectsEndToEnd(Path home, Path docs) throws Exception {
        section("[10] Isolated project workspaces + actions through the full stack");
        Files.createDirectories(docs);
        writeCorpus(docs);
        Path extra = write(docs.resolve("silinecek.txt"), "Silinecek taslak belge: geçici notlar.");
        CountingIngestor ingestor = new CountingIngestor();
        ExtendedWorkbenchController.Services services = new ExtendedWorkbenchController.Services(ingestor,
                new QuotaManager(QuotaManager.Config.defaults()), Optional::empty,
                () -> new OsShellBridge(OsShellBridge.Platform.WINDOWS, recorder(new CopyOnWriteArrayList<>()), false),
                new FakeTransport(null), null, null);
        ExtendedWorkbenchController.Config config = new ExtendedWorkbenchController.Config(home, "Alfa", null,
                WebSearchBridge.Config.disabled(), Duration.ofMillis(200), true);
        try (ExtendedWorkbenchController ext = new ExtendedWorkbenchController(config, services)) {
            ext.start();
            WorkbenchController alfa = ext.controller();
            alfa.addPath(docs, alfa.terminal().output());
            int before = alfa.workbench().documents().size();
            check("[10] project file exists", Files.isRegularFile(home.resolve("projects/alfa/project.dwbproj")), "");

            RemovalResult purged = alfa.purge(byName(alfa, "silinecek.txt").sha256());
            alfa.untrack(byName(alfa, "fatura.txt").sha256());
            check("purge through the project controller deletes the file", purged.ok() && !Files.exists(extra),
                    purged.describe());
            waitFor(() -> readQuietly(home.resolve("audit.log")).contains("DELETE"));
            check("project audit.log records the deletion", readQuietly(home.resolve("audit.log")).contains("DELETE")
                    && readQuietly(home.resolve("audit.log")).contains("UNTRACK"), "");

            ext.projects().create("Beta", null);
            SwitchReport toBeta = ext.projects().switchTo(ext.projects().find("Beta").orElseThrow());
            WorkbenchController beta = ext.controller();
            check("[10] switching saves the outgoing project (index.dwb)", toBeta.saved() != null
                    && Files.isRegularFile(home.resolve("projects/alfa/index.dwb")), toBeta.describe());
            check("[10] new project is isolated and empty", beta != alfa && beta.workbench().documents().isEmpty()
                    && beta.search("kira", 5).isEmpty(), "");
            check("[10] focus coordinator re-created for the new project", !ext.focus().focusActive(), "");

            SwitchReport back = ext.projects().switchTo(ext.projects().find("Alfa").orElseThrow());
            WorkbenchController alfaAgain = ext.controller();
            check("[10] switching back restores exactly the kept documents", back.startup().warmed() == before - 2
                    && alfaAgain.workbench().documents().stream().noneMatch(d -> d.fileName().equals("fatura.txt")
                    || d.fileName().equals("silinecek.txt")), names(alfaAgain));
            check("[10] project list offers both catalogues", ext.projects().list().size() >= 2, "");
        }
    }

    // ================================================================== heap under load

    private void heapUnderLoad(Path dir) throws Exception {
        section("Heap compliance under load");
        Path docs = Files.createDirectories(dir.resolve("docs"));
        String paragraph = "Tedarik sözleşmesi hükümleri, teslimat takvimi, ödeme koşulları ve cezai şartlar. ";
        for (int i = 0; i < 250; i++) {
            write(docs.resolve(String.format(Locale.ROOT, "belge-%03d.txt", i)),
                    ("Belge " + i + ": " + paragraph).repeat(250));
        }
        try (WorkbenchController c = controller(dir.resolve("data"), new ArrayList<>())) {
            int added = c.addPath(docs, c.terminal().output());
            long max = Runtime.getRuntime().maxMemory();
            System.gc();
            long loaded = usedHeap();
            check("250 documents (~5 MB text) indexed", added == 250, "added " + added);
            check("heap after ingestion stays below 60% of the ceiling", loaded < max * 0.6,
                    (loaded >> 20) + " MB of " + (max >> 20) + " MB");
            List<String> sink = new ArrayList<>();
            for (DocumentRecord d : c.workbench().documents()) {
                c.copyContent(d, sink::add);
                sink.clear();
            }
            for (DocumentRecord d : List.copyOf(c.workbench().documents())) {
                c.untrack(d.sha256());
            }
            System.gc();
            long after = usedHeap();
            check("untracking everything returns the heap", c.workbench().documents().isEmpty() && after < loaded,
                    (loaded >> 20) + " MB -> " + (after >> 20) + " MB");
            check("peak heap never exceeded 512 MB", peakHeapBytes() <= HEAP_LIMIT, (peakHeapBytes() >> 20) + " MB");
        }
    }

    // ================================================================== fixtures

    private static WorkbenchController controller(Path data, List<List<String>> launched) {
        return new WorkbenchController(new Workbench(new DocumentParser(), new InvertedIndex(), null),
                WorkbenchController.Config.defaults(data),
                new OsShellBridge(OsShellBridge.Platform.WINDOWS, recorder(launched), false));
    }

    private static void writeCorpus(Path dir) throws IOException {
        write(dir.resolve("kira.txt"), """
                KİRA SÖZLEŞMESİ
                Madde 4: Aylık kira bedeli 25.000 TL olup her ayın beşinde peşin ödenir.
                Madde 5: Kira artış oranı her yıl TÜFE on iki aylık ortalaması kadardır.
                """);
        write(dir.resolve("fatura.txt"), "Fatura No 2026-118. Hizmet bedeli 4.200 TL.");
        write(dir.resolve("tablo.csv"), "Ad;Tutar;Tarih\nAyşe;1200;2026-01-05\nMehmet;950;2026-02-11\n");
    }

    private static Path write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static DocumentRecord byName(WorkbenchController c, String name) {
        return c.workbench().documents().stream().filter(d -> d.fileName().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException("not indexed: " + name + " in " + names(c)));
    }

    private static String names(WorkbenchController c) {
        return c.workbench().documents().stream().map(DocumentRecord::fileName).sorted().toList().toString();
    }

    private static String stroke(KeyMapRegistry keys, Action action) {
        return keys.strokeFor(action).map(Object::toString).orElse("(none)");
    }

    private static WebSearchBridge.Config webConfig() {
        return new WebSearchBridge.Config(WebSearchBridge.Provider.SEARXNG, URI.create("http://searx.intranet"), null,
                Duration.ofSeconds(2), 8, 512 << 10, 600, 4, 4_000, Duration.ofSeconds(60));
    }

    private static String readQuietly(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return "";
        }
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    /** Sum of the heap pools' peak usage since JVM start. */
    private static long peakHeapBytes() {
        long peak = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                peak += pool.getPeakUsage().getUsed();
            }
        }
        return peak;
    }

    private static void writeBytes(Path file, long size) throws IOException {
        byte[] block = "a;b;c\n".repeat(10_000).getBytes(StandardCharsets.US_ASCII);
        try (OutputStream out = Files.newOutputStream(file)) {
            long left = size;
            while (left > 0) {
                int n = (int) Math.min(block.length, left);
                out.write(block, 0, n);
                left -= n;
            }
        }
    }

    private static OsShellBridge.ProcessLauncher recorder(List<List<String>> sink) {
        return command -> {
            sink.add(List.copyOf(command));
            return fakeProcess();
        };
    }

    private static Process fakeProcess() {
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
