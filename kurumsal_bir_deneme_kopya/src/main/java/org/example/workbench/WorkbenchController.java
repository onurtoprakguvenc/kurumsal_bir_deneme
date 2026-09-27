package org.example.workbench;

import org.example.core.DocumentIngestor.IngestResult;
import org.example.core.HeapGuard;
import org.example.core.IngestionException;
import org.example.core.SearchIndex;
import org.example.core.SystemFixture;
import org.example.core.Workbench;
import org.example.index.Tokenizer;
import org.example.input.KeyMapRegistry;
import org.example.input.KeyMapRegistry.Action;
import org.example.input.KeyMapRegistry.BindResult;
import org.example.input.KeyMapRegistry.KeyStroke;
import org.example.model.BinaryAsset;
import org.example.model.ContentKind;
import org.example.model.DocumentRecord;
import org.example.model.SearchHit;
import org.example.model.SearchResult;
import org.example.model.TextChunk;
import org.example.p2p.ContentStore;
import org.example.p2p.LanSyncService;
import org.example.platform.ClipboardPort;
import org.example.platform.OsShellBridge;
import org.example.platform.OsShellBridge.ShellResult;
import org.example.repl.InternalTerminalEngine;
import org.example.repl.InternalTerminalEngine.CommandSpec;
import org.example.repl.InternalTerminalEngine.Invocation;
import org.example.repl.InternalTerminalEngine.Output;
import org.example.state.PanelStateCoordinator;
import org.example.state.PanelStateCoordinator.Panel;
import org.example.state.Subscription;
import org.example.state.ViewModeCoordinator;
import org.example.state.ViewModeCoordinator.MemorySnapshot;
import org.example.state.ViewModeCoordinator.ViewMode;
import org.example.storage.IndexStorageEngine;
import org.example.storage.IndexStorageEngine.Entry;
import org.example.storage.IndexStorageEngine.LoadReport;
import org.example.storage.IndexStorageEngine.SaveReport;
import org.example.storage.MetadataRecord;
import org.example.util.Hashing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Central orchestrator of the workbench shell: persistence, OS actions, the terminal, view modes, panels and key
 * bindings, wired around the existing headless {@link Workbench} core.
 *
 * <p>This class is the hexagon's driving adapter for every surface. The JavaFX layer calls the same methods the
 * terminal commands call ({@link #search}, {@link #openRow}, {@link #dispatch}, …) and observes state through the
 * coordinators' listener hooks, so everything here runs — and is verified — without a toolkit.</p>
 *
 * <p>Threading: blocking work (snapshot I/O, ingestion, process launch) runs on virtual threads or on the terminal's
 * own virtual thread; methods returning {@link CompletableFuture} never block the caller. The only UI callback,
 * {@link UiHooks#focusSearch()}, is invoked on the calling thread and must hop to the FX thread itself.</p>
 */
public final class WorkbenchController implements AutoCloseable {

    /** What to do with a snapshot document whose local source changed since it was indexed. */
    public enum StalePolicy {
        /** Serve the snapshot text anyway. */
        KEEP,
        /**
         * Re-ingest the current file in the background; the snapshot version stays searchable until the new one has
         * been extracted, and is kept if extraction fails (file locked, unreadable).
         */
        REINGEST
    }

    /**
     * @param dataDir     directory holding {@code index.dwb} and {@code keybindings.properties}
     * @param defaultTop  result count when {@code search} is called without {@code --top}
     * @param stalePolicy handling of changed sources at startup (missing local sources are always dropped)
     */
    public record Config(Path dataDir, int defaultTop, StalePolicy stalePolicy) {
        public Config {
            Objects.requireNonNull(dataDir, "dataDir must not be null");
            defaultTop = Math.max(1, Math.min(defaultTop, 200));
            stalePolicy = stalePolicy == null ? StalePolicy.REINGEST : stalePolicy;
        }

        public static Config defaults(Path dataDir) {
            return new Config(dataDir, 10, StalePolicy.REINGEST);
        }

        public Path indexFile() {
            return dataDir.resolve("index.dwb");
        }

        public Path keyBindingsFile() {
            return dataDir.resolve("keybindings.properties");
        }

        /** Pins, tags, saved searches and recent documents ({@link ProjectNotes}). */
        public Path notesFile() {
            return dataDir.resolve("notes.properties");
        }
    }

    /** Callbacks only a UI can fulfil. All methods default to no-ops for headless use. */
    public interface UiHooks {
        default void focusSearch() {
        }

        UiHooks NONE = new UiHooks() {
        };
    }

    /**
     * Admission rules applied to every file the controller ingests: exclusion patterns, memory ceilings and bulk
     * parallelism. Installed by an administration layer; the default admits everything, one file at a time.
     */
    public interface IngestPolicy {
        /** True for files or directories that must never be indexed; excluded directories are pruned from walks. */
        boolean excluded(Path path);

        /**
         * Exclusion of {@code path} found below {@code root} (the folder the user added). Name patterns are meant for
         * what lies inside the added folder, so implementations may ignore the folders above {@code root}: a project
         * stored under, say, {@code D:\archive.bak\} must not be excluded as a whole. Defaults to
         * {@link #excluded(Path)}.
         */
        default boolean excluded(Path root, Path path) {
            return excluded(path);
        }

        /** A reason to refuse ingesting {@code file} right now (e.g. heap above the ceiling), or empty to admit. */
        default Optional<IngestionException> refusal(Path file) {
            return Optional.empty();
        }

        /** Maximum concurrent extractions during bulk adds. */
        default int parallelism() {
            return 1;
        }

        IngestPolicy OPEN = path -> false;
    }

    /** Observable facts for auditing and diagnostics, emitted on the thread where they happen. */
    public sealed interface WorkbenchEvent permits WorkbenchEvent.IngestFailed, WorkbenchEvent.IngestRefused,
            WorkbenchEvent.FileAction, WorkbenchEvent.CommandFailed, WorkbenchEvent.Ingested,
            WorkbenchEvent.AssetRegistered, WorkbenchEvent.Removed {

        record Ingested(DocumentRecord document, long parseMillis) implements WorkbenchEvent {
        }

        /**
         * A binary file (video, image, audio, archive) was accepted: hashed and registered in the project's
         * {@link ContentStore} for sharing, without text extraction and without entering the search index.
         */
        record AssetRegistered(BinaryAsset asset, long hashMillis) implements WorkbenchEvent {
        }

        record IngestFailed(Path file, IngestionException.Reason reason, String message) implements WorkbenchEvent {
        }

        record IngestRefused(Path file, String reason) implements WorkbenchEvent {
        }

        record FileAction(ShellResult result) implements WorkbenchEvent {
        }

        record CommandFailed(String line, String message) implements WorkbenchEvent {
        }

        /** A document left the index at the user's request; {@code fromDisk} when its source file was deleted too. */
        record Removed(DocumentRecord document, boolean fromDisk) implements WorkbenchEvent {
        }
    }

    /** Outcome of {@link #untrack} and {@link #purge}. Messages are user-facing (Turkish, like the rest of the UI). */
    public sealed interface RemovalResult permits RemovalResult.Untracked, RemovalResult.Purged,
            RemovalResult.Rejected, RemovalResult.Failed {

        /** Removed from the index; the file on disk was not touched. */
        record Untracked(DocumentRecord document) implements RemovalResult {
        }

        /** The source file was deleted and every index entry pointing at it removed. */
        record Purged(DocumentRecord document, Path file, int entries) implements RemovalResult {
        }

        /** Refused by a safety check before anything was changed. */
        record Rejected(String docId, String reason) implements RemovalResult {
        }

        /** Deleting was attempted and the file system refused; the index is unchanged. */
        record Failed(DocumentRecord document, String reason) implements RemovalResult {
        }

        default boolean ok() {
            return this instanceof Untracked || this instanceof Purged;
        }

        default String describe() {
            return switch (this) {
                case Untracked u -> u.document().fileName() + " indeksten kaldırıldı (dosya diskte duruyor)";
                case Purged p -> p.document().fileName() + " diskten silindi"
                        + (p.entries() > 1 ? " (" + p.entries() + " indeks kaydı kaldırıldı)" : "");
                case Rejected r -> "İşlem reddedildi: " + r.reason();
                case Failed f -> f.document().fileName() + " silinemedi: " + f.reason();
            };
        }
    }

    /**
     * @param reingest completes with the number of changed sources re-ingested in the background
     */
    public record StartupReport(LoadReport load, int warmed, int dropped, int stale, int notWarmed, long warmNanos,
                                List<KeyMapRegistry.Issue> keyIssues, CompletableFuture<Integer> reingest) {
        public double warmMillis() {
            return warmNanos / 1_000_000.0;
        }
    }

    /** Facts about how a document entered the index; persisted in its {@link MetadataRecord}. */
    private record IngestInfo(long parseMillis, long sourceModified) {
    }

    private static final int MAX_WALK_DEPTH = 8;
    /** "Copy content" cap: 2M chars is ~4 MB of UTF-16, well inside the heap budget and any clipboard's limits. */
    public static final int MAX_COPY_CHARS = 2_000_000;
    private static final long SHELL_WAIT_SECONDS = 10;

    private final Workbench workbench;
    private final Config config;
    private final IndexStorageEngine storage;
    private final KeyMapRegistry keys;
    private final OsShellBridge shell;
    private final ViewModeCoordinator view;
    private final PanelStateCoordinator panels = new PanelStateCoordinator();
    private final InternalTerminalEngine terminal = new InternalTerminalEngine();
    private final Tokenizer tokenizer = new Tokenizer();
    private final ConcurrentHashMap<String, IngestInfo> ingestInfo = new ConcurrentHashMap<>();
    /** Pins, tags, saved searches and recent documents of this project. */
    private final ProjectNotes notes;
    /** PDFs refused in this session because no page had a text layer (listed by {@code ocr-needed}). */
    private final Set<Path> scannedOnly = ConcurrentHashMap.newKeySet();
    /** Arguments of the last {@code search} command, for {@code saved --add}. */
    private volatile String lastSearchLine;
    /** Clipboard supplied by a UI; {@code null} when headless ({@code cite} then only prints). */
    private volatile ClipboardPort clipboard;
    /** Snapshot documents the heap ceiling kept out of this session's index; preserved on every save. */
    private final Set<String> unwarmed = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Content-addressed store for binary assets (and, with LAN sync, shared documents); created on first use. */
    private ContentStore contentStore;
    private volatile UiHooks hooks = UiHooks.NONE;
    private volatile IngestPolicy policy = IngestPolicy.OPEN;
    private final CopyOnWriteArrayList<Consumer<WorkbenchEvent>> eventListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<String>> longHashListeners = new CopyOnWriteArrayList<>();
    private final EnumMap<Action, Runnable> actionOverrides = new EnumMap<>(Action.class);
    private volatile int selectedRow = -1;
    private volatile long savedFingerprint;

    public WorkbenchController(Workbench workbench, Config config, OsShellBridge shell) {
        this.workbench = Objects.requireNonNull(workbench, "workbench must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.shell = Objects.requireNonNull(shell, "shell must not be null");
        this.storage = new IndexStorageEngine(config.indexFile());
        this.keys = new KeyMapRegistry(config.keyBindingsFile());
        this.view = new ViewModeCoordinator(new IndexTelemetry(), ViewMode.SIMPLE);
        this.notes = new ProjectNotes(config.notesFile());
        this.savedFingerprint = fingerprint(workbench.documents());
        registerCommands();
        terminal.onOutcome(outcome -> {
            if (outcome instanceof InternalTerminalEngine.Outcome.Failed(String line, String message)) {
                emit(new WorkbenchEvent.CommandFailed(line, message));
            }
        });
    }

    /** {@code %LOCALAPPDATA%\DocumentWorkbench} on Windows, {@code ~/.document-workbench} elsewhere. */
    public static Path defaultDataDir() {
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) {
            return Path.of(local, "DocumentWorkbench");
        }
        return Path.of(System.getProperty("user.home"), ".document-workbench");
    }

    // ================================================================== accessors (UI binding points)

    public Workbench workbench() {
        return workbench;
    }

    public ViewModeCoordinator view() {
        return view;
    }

    public PanelStateCoordinator panels() {
        return panels;
    }

    public InternalTerminalEngine terminal() {
        return terminal;
    }

    public KeyMapRegistry keys() {
        return keys;
    }

    public IndexStorageEngine storage() {
        return storage;
    }

    public void setUiHooks(UiHooks hooks) {
        this.hooks = hooks == null ? UiHooks.NONE : hooks;
    }

    public void setIngestPolicy(IngestPolicy policy) {
        this.policy = policy == null ? IngestPolicy.OPEN : policy;
    }

    public IngestPolicy ingestPolicy() {
        return policy;
    }

    public Subscription onEvent(Consumer<WorkbenchEvent> listener) {
        eventListeners.add(Objects.requireNonNull(listener));
        return () -> eventListeners.remove(listener);
    }

    /** Files at least this large take seconds to minutes to hash; listeners are told before it starts. */
    public static final long LONG_HASH_BYTES = 256L << 20;

    /**
     * Called with the file name (on the ingesting thread) before a file of at least {@link #LONG_HASH_BYTES} is
     * hashed, so a UI can show that the work is in progress instead of looking frozen. Listeners must not block.
     */
    public Subscription onLongHash(Consumer<String> listener) {
        longHashListeners.add(Objects.requireNonNull(listener));
        return () -> longHashListeners.remove(listener);
    }

    private void announceLongHash(Path file) {
        if (longHashListeners.isEmpty()) {
            return;
        }
        long size;
        try {
            size = Files.size(file);
        } catch (IOException | RuntimeException e) {
            return;
        }
        if (size < LONG_HASH_BYTES) {
            return;
        }
        String label = file.getFileName() + " (" + String.format(java.util.Locale.ROOT, "%.1f GB", size / 1e9) + ")";
        for (Consumer<String> l : longHashListeners) {
            try {
                l.accept(label);
            } catch (RuntimeException e) {
                System.getLogger(WorkbenchController.class.getName())
                        .log(System.Logger.Level.WARNING, "Long-hash listener failed", e);
            }
        }
    }

    private void emit(WorkbenchEvent event) {
        for (Consumer<WorkbenchEvent> l : eventListeners) {
            try {
                l.accept(event);
            } catch (RuntimeException e) {
                System.getLogger(WorkbenchController.class.getName())
                        .log(System.Logger.Level.WARNING, "Event listener failed", e);
            }
        }
    }

    /** Routes {@code action} to {@code handler} instead of the built-in behaviour; close to restore. */
    public Subscription overrideAction(Action action, Runnable handler) {
        Objects.requireNonNull(handler);
        synchronized (actionOverrides) {
            actionOverrides.put(action, handler);
        }
        return () -> {
            synchronized (actionOverrides) {
                actionOverrides.remove(action, handler);
            }
        };
    }

    // ================================================================== lifecycle

    /**
     * Loads key bindings and the index snapshot, warming the index from stored chunk text (no document is
     * re-parsed). Changed local sources are re-ingested in the background according to {@link Config#stalePolicy()}.
     */
    public StartupReport startup() throws IOException {
        Output out = terminal.output();
        List<KeyMapRegistry.Issue> keyIssues = keys.load();
        for (KeyMapRegistry.Issue issue : keyIssues) {
            out.println("keys: " + issue);
        }
        try {
            notes.load();
        } catch (IOException e) {
            out.println("notes: " + notes.file().getFileName() + " unreadable (" + e.getMessage()
                    + "); pins, tags and saved searches start empty");
        }

        long started = System.nanoTime();
        HeapGuard guard = new HeapGuard(HeapGuard.DEFAULT_CEILING);
        SearchIndex index = workbench.index();
        List<Stale> changed = new ArrayList<>();
        int[] counts = new int[3]; // warmed, dropped, notWarmed
        unwarmed.clear();
        // Streamed: each document is indexed as soon as it is decoded, so the snapshot is never held in memory as a
        // whole, and the heap check sees the real cost of everything warmed so far.
        LoadReport load = storage.load(new IndexStorageEngine.Visitor() {
            @Override
            public boolean wants(MetadataRecord meta) {
                if (meta.local() && meta.freshness() == MetadataRecord.Freshness.MISSING) {
                    counts[1]++;
                    return false;
                }
                if (!guard.hasHeadroom(estimatedIndexBytes(meta.charCount()))) {
                    // Kept in the snapshot (carried over on save) and warmed on a later start with more headroom.
                    unwarmed.add(meta.sha256());
                    counts[2]++;
                    return false;
                }
                return true;
            }

            @Override
            public void entry(Entry entry) {
                MetadataRecord meta = entry.metadata();
                if (!index.add(entry.document())) {
                    return;
                }
                ingestInfo.put(meta.sha256(), new IngestInfo(meta.parseMillis(), meta.sourceModifiedMillis()));
                counts[0]++;
                if (meta.local() && config.stalePolicy() == StalePolicy.REINGEST
                        && meta.freshness() == MetadataRecord.Freshness.CHANGED) {
                    // Served from the snapshot until the new version has been extracted successfully.
                    changed.add(new Stale(meta.sha256(), Path.of(meta.source())));
                }
            }
        });
        for (String problem : load.problems()) {
            out.println("snapshot rejected: " + problem);
        }
        int warmed = counts[0];
        int dropped = counts[1];
        int notWarmed = counts[2];
        if (notWarmed > 0) {
            out.println("memory ceiling reached (" + guard.describe() + "); " + notWarmed
                    + " documents left out of the index for this session (they stay in the snapshot)");
        }
        long warmNanos = System.nanoTime() - started;
        // Everything just restored is already on disk (documents left out are carried over on save); only deleted
        // sources make the index dirty. Re-ingested versions change the fingerprint when they land.
        savedFingerprint = dropped == 0 ? fingerprint(workbench.documents()) : 0;

        if (load.origin() != IndexStorageEngine.Origin.NONE) {
            out.printf("index warmed from %s (%s): %d documents in %.1f ms read + %.1f ms index%s",
                    storage.file().getFileName(), load.origin(), warmed, load.millis(), warmNanos / 1e6,
                    dropped > 0 ? ", " + dropped + " missing sources dropped" : "");
        }
        installSystemFixture(out);
        CompletableFuture<Integer> reingest = changed.isEmpty()
                ? CompletableFuture.completedFuture(0)
                : CompletableFuture.supplyAsync(() -> reingestChanged(changed),
                        r -> Thread.ofVirtual().name("dwb-reingest").start(r));
        return new StartupReport(load, warmed, dropped, changed.size(), notWarmed, warmNanos, keyIssues, reingest);
    }

    /** A warmed snapshot document whose source changed since it was indexed. */
    private record Stale(String sha256, Path source) {
    }

    /**
     * Indexes the permanent, read-only demo report ({@link SystemFixture}) from {@code <dataDir>/system}. It only
     * joins BM25 search: {@link Workbench#documents()} hides it, so it is never listed, counted, saved or purged.
     */
    private void installSystemFixture(Output out) {
        try {
            SystemFixture.install(workbench, config.dataDir().resolve("system"));
        } catch (IOException | RuntimeException e) {
            out.println("demo fixture unavailable: " + e.getMessage());
        }
    }

    private int reingestChanged(List<Stale> sources) {
        Output out = terminal.output();
        out.println(sources.size() + " changed source(s) are being re-indexed in the background");
        int done = 0;
        for (Stale stale : sources) {
            if (closed.get()) {
                return done; // the project was unloaded; its next start re-checks the sources
            }
            try {
                if (reindex(stale.sha256(), stale.source()) instanceof IngestResult.Ingested) {
                    done++;
                }
            } catch (IngestionException | RuntimeException e) {
                out.println("re-index failed for " + stale.source().getFileName() + ": " + e.getMessage()
                        + " (the previously indexed version is kept)");
            }
        }
        out.println("background re-index finished: " + done + "/" + sources.size());
        return done;
    }

    /** True when the index differs from the last snapshot written or loaded. */
    public boolean dirty() {
        return fingerprint(workbench.documents()) != savedFingerprint;
    }

    /** Writes the snapshot synchronously. */
    public SaveReport save() throws IOException {
        List<DocumentRecord> documents = workbench.documents();
        SaveReport report = storage.save(documents, this::metadata, Set.copyOf(unwarmed));
        savedFingerprint = fingerprint(documents);
        return report;
    }

    /** Writes the snapshot on a virtual thread; progress and errors go to the terminal. */
    public CompletableFuture<SaveReport> saveAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                SaveReport report = save();
                terminal.output().printf("index saved: %d documents, %d KB in %.1f ms", report.documents(),
                        report.bytes() >> 10, report.millis());
                return report;
            } catch (IOException e) {
                terminal.output().error("save failed: " + e.getMessage());
                throw new UncheckedIOException(e);
            }
        }, r -> Thread.ofVirtual().name("dwb-save").start(r));
    }

    /** Metadata as it would be persisted: parse latency and the source fingerprint observed at ingestion. */
    public MetadataRecord metadata(DocumentRecord document) {
        IngestInfo info = ingestInfo.get(document.sha256());
        return info == null
                ? MetadataRecord.of(document, -1)
                : MetadataRecord.of(document, info.parseMillis(), info.sourceModified());
    }

    /** Saves when dirty, then releases the terminal and shell threads. Idempotent. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            if (dirty()) {
                save();
            }
        } catch (IOException e) {
            System.getLogger(WorkbenchController.class.getName())
                    .log(System.Logger.Level.WARNING, "Index snapshot not saved on shutdown", e);
        } finally {
            terminal.close();
            shell.close();
        }
    }

    // ================================================================== documents

    /**
     * Ingests one file through the {@link IngestPolicy} and records its parse latency and source fingerprint for
     * the snapshot. Refusals and failures are also emitted as {@link WorkbenchEvent}s.
     */
    public IngestResult ingest(Path file) throws IngestionException {
        return ingest(file, null);
    }

    /**
     * @param root the folder the user added when {@code file} was found by a walk, or {@code null} for a file added
     *             directly (then its whole path is checked against the exclusion list)
     */
    private IngestResult ingest(Path file, Path root) throws IngestionException {
        admit(file, root, true);
        long modified = MetadataRecord.modifiedMillis(file);
        announceLongHash(file);
        IngestResult result;
        try {
            result = workbench.ingest(file, null);
        } catch (IngestionException e) {
            noteScanned(file, e);
            emit(new WorkbenchEvent.IngestFailed(file, e.reason(), e.getMessage()));
            throw e;
        }
        scannedOnly.remove(file.toAbsolutePath().normalize());
        recordIngested(file, result, modified);
        return result;
    }

    /** A PDF refused because none of its pages has a text layer is remembered for {@code ocr-needed}. */
    private void noteScanned(Path file, IngestionException e) {
        if (e.reason() == IngestionException.Reason.EMPTY && String.valueOf(e.getMessage()).contains("no text layer")) {
            scannedOnly.add(file.toAbsolutePath().normalize());
        }
    }

    /**
     * Re-ingests {@code file} as the new version of the indexed document {@code sha256} (watcher, rebuild, changed
     * sources at startup). Unlike remove-then-ingest, the indexed version is only replaced once the new one has been
     * extracted: a file that is locked by the application saving it, half-written or momentarily unreadable leaves
     * the document searchable in its previous version, and the exception reports why.
     */
    public IngestResult reindex(String sha256, Path file) throws IngestionException {
        // Exclusions decide what enters the index; a document already in it is kept current (the health audit
        // reports indexed documents that match a newer exclusion). The memory ceiling still applies.
        admit(file, null, false);
        long modified = MetadataRecord.modifiedMillis(file);
        String origin = document(sha256).map(DocumentRecord::origin).orElse(DocumentRecord.LOCAL);
        IngestResult result;
        try {
            result = workbench.replace(sha256, file, null, origin);
        } catch (IngestionException e) {
            emit(new WorkbenchEvent.IngestFailed(file, e.reason(), e.getMessage()));
            throw e;
        }
        if (!result.sha256().equals(sha256) && !workbench.index().contains(sha256)) {
            ingestInfo.remove(sha256);
            notes.migrate(sha256, result.sha256()); // pins and tags follow the new version
        }
        recordIngested(file, result, modified);
        return result;
    }

    /** Applies the ingest policy (exclusions, memory ceiling); refusals are emitted and thrown. */
    private void admit(Path file, Path root, boolean checkExclusions) throws IngestionException {
        IngestPolicy active = policy;
        if (checkExclusions && (root == null ? active.excluded(file) : active.excluded(root, file))) {
            emit(new WorkbenchEvent.IngestRefused(file, "excluded by policy"));
            throw new IngestionException(IngestionException.Reason.UNSUPPORTED, "excluded by policy: " + file);
        }
        Optional<IngestionException> refusal = active.refusal(file);
        if (refusal.isPresent()) {
            emit(new WorkbenchEvent.IngestRefused(file, refusal.get().getMessage()));
            throw refusal.get();
        }
    }

    /**
     * Success bookkeeping. Documents get their parse facts and an {@link WorkbenchEvent.Ingested}; binary assets
     * bypass the index entirely and are registered in the {@link #contentStore()} by SHA-256, then announced with
     * {@link WorkbenchEvent.AssetRegistered}. A store that cannot record the asset is reported as a failure.
     */
    private void recordIngested(Path file, IngestResult result, long modified) throws IngestionException {
        switch (result) {
            case IngestResult.Ingested(DocumentRecord document, long millis) -> {
                ingestInfo.put(document.sha256(), new IngestInfo(millis, modified));
                emit(new WorkbenchEvent.Ingested(document, millis));
            }
            case IngestResult.Registered(BinaryAsset asset, long millis) -> {
                try {
                    contentStore().register(asset.sha256(), asset.source(), asset.fileName(), ContentKind.BINARY);
                } catch (IOException | RuntimeException e) {
                    IngestionException failure = new IngestionException(IngestionException.Reason.IO_ERROR,
                            "binary file could not be registered for sharing: " + e.getMessage(), e);
                    emit(new WorkbenchEvent.IngestFailed(file, failure.reason(), failure.getMessage()));
                    throw failure;
                }
                emit(new WorkbenchEvent.AssetRegistered(asset, millis));
            }
            case IngestResult.Duplicate duplicate -> {
            }
        }
    }

    /** The project's content store ({@code <dataDir>/lan}); LAN sync serves from this same instance. */
    public synchronized ContentStore contentStore() throws IOException {
        if (contentStore == null) {
            contentStore = new ContentStore(config.dataDir().resolve(LanSyncService.STORE_DIR));
        }
        return contentStore;
    }

    /** Binary assets registered from this project's disk (hashed, shareable, not in the search index). */
    public List<BinaryAsset> assets() {
        try {
            return contentStore().registeredAssets();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * A registered binary asset by SHA-256 prefix (4+ hex characters), exact file name, or unambiguous
     * case-insensitive name fragment — the same rules the workspace catalog uses for documents.
     */
    public Optional<BinaryAsset> resolveAsset(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String lower = token.strip().toLowerCase(Locale.ROOT);
        List<BinaryAsset> all = assets();
        List<List<BinaryAsset>> tiers = List.of(
                lower.length() >= 4 ? all.stream().filter(a -> a.sha256().startsWith(lower)).toList() : List.of(),
                all.stream().filter(a -> a.fileName().toLowerCase(Locale.ROOT).equals(lower)).toList(),
                all.stream().filter(a -> a.fileName().toLowerCase(Locale.ROOT).contains(lower)).toList());
        for (List<BinaryAsset> candidates : tiers) {
            if (candidates.size() == 1) {
                return Optional.of(candidates.getFirst());
            }
            if (candidates.size() > 1) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Puts the asset's absolute path on the clipboard and returns it. */
    public String copyPath(BinaryAsset asset, ClipboardPort clipboard) {
        String path = asset.source().toAbsolutePath().normalize().toString();
        clipboard.putText(path);
        return path;
    }

    /** Stops sharing a registered binary asset; the file on disk is not touched. */
    public boolean removeAsset(String sha256) {
        try {
            ContentStore store = contentStore();
            if (store.registeredKind(sha256).orElse(null) != ContentKind.BINARY) {
                return false;
            }
            store.unregister(sha256);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Ingests a file or every supported file below a directory. Excluded directories are pruned rather than walked
     * (so {@code node_modules} costs nothing), unreadable directories are skipped, and up to
     * {@link IngestPolicy#parallelism()} files are extracted concurrently on virtual threads.
     */
    public int addPath(Path root, Output out) throws IOException {
        List<Path> files = new ArrayList<>();
        IngestPolicy active = policy;
        if (Files.isDirectory(root)) {
            int[] tooDeep = {0};
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), MAX_WALK_DEPTH,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            return !dir.equals(root) && active.excluded(root, dir)
                                    ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (attrs.isDirectory()) {
                                // Directories at the depth limit are reported to visitFile instead of being entered.
                                if (!active.excluded(root, file)) {
                                    tooDeep[0]++;
                                }
                            } else if (attrs.isRegularFile() && workbench.supports(file)
                                    && !active.excluded(root, file)) {
                                files.add(file);
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException e) {
                            out.println("  ! " + file + ": " + e.getMessage());
                            return FileVisitResult.CONTINUE;
                        }
                    });
            if (tooDeep[0] > 0) {
                out.println("  ! " + tooDeep[0] + " folder(s) deeper than " + MAX_WALK_DEPTH
                        + " levels were not scanned; add them directly to index their files");
            }
            files.sort(null);
        } else if (Files.isRegularFile(root)) {
            files.add(root);
        } else {
            throw new IOException("not found: " + root);
        }
        Path walkRoot = Files.isDirectory(root) ? root : null;
        int workers = Math.max(1, Math.min(active.parallelism(), 16));
        AtomicInteger added = new AtomicInteger();
        if (workers == 1 || files.size() < 2) {
            for (Path file : files) {
                ingestReporting(file, walkRoot, out, added);
            }
            return added.get();
        }
        Semaphore permits = new Semaphore(workers);
        try (ExecutorService pool = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("dwb-ingest-", 0).factory())) {
            for (Path file : files) {
                pool.submit(() -> {
                    permits.acquireUninterruptibly();
                    try {
                        ingestReporting(file, walkRoot, out, added);
                    } finally {
                        permits.release();
                    }
                });
            }
        }
        return added.get();
    }

    private void ingestReporting(Path file, Path walkRoot, Output out, AtomicInteger added) {
        try {
            switch (ingest(file, walkRoot)) {
                case IngestResult.Ingested(DocumentRecord d, long millis) -> {
                    added.incrementAndGet();
                    out.printf("  + %s (%d chunks, %d ms)", d.fileName(), d.chunks().size(), millis);
                }
                case IngestResult.Duplicate(String sha, Path p) -> out.println("  = " + p.getFileName()
                        + " (already indexed)");
                case IngestResult.Registered(BinaryAsset a, long millis) -> {
                    added.incrementAndGet();
                    out.printf("  + %s (binary, %,d bytes, registered for sharing, not indexed, %d ms)", a.fileName(),
                            a.sizeBytes(), millis);
                }
            }
        } catch (IngestionException | RuntimeException e) {
            out.println("  ! " + file.getFileName() + ": " + e.getMessage());
        }
    }

    public boolean remove(String sha256) {
        boolean removed = workbench.remove(sha256);
        if (removed) {
            ingestInfo.remove(sha256);
        }
        return removed;
    }

    /**
     * Re-extracts every local document from its source and rebuilds all postings from scratch. Documents whose
     * source is gone are removed; peer documents are kept as they are.
     *
     * @return number of documents re-ingested
     */
    public int rebuild(Output out) {
        List<DocumentRecord> local = workbench.documents().stream().filter(DocumentRecord::local).toList();
        int reingested = 0;
        for (DocumentRecord document : local) {
            if (Thread.currentThread().isInterrupted()) {
                out.println("rebuild interrupted");
                break;
            }
            MetadataRecord.Freshness freshness = MetadataRecord.of(document, -1, 0).freshness();
            if (freshness == MetadataRecord.Freshness.MISSING) {
                remove(document.sha256());
                out.println("  - " + document.fileName() + " (source missing, removed)");
                continue;
            }
            if (!Files.isRegularFile(document.source())) {
                out.println("  ! " + document.fileName() + ": source not reachable right now; indexed version kept");
                continue;
            }
            try {
                // The indexed version stays until the new extraction succeeds; postings are rebuilt either way
                // because the fresh extraction replaces the old record.
                IngestInfo info = ingestInfo.get(document.sha256());
                remove(document.sha256());
                IngestResult result;
                try {
                    result = ingest(document.source());
                } catch (IngestionException | RuntimeException e) {
                    if (workbench.index().add(document) && info != null) { // restore the previous version
                        ingestInfo.put(document.sha256(), info);
                    }
                    throw e;
                }
                if (result instanceof IngestResult.Ingested(DocumentRecord d, long millis)) {
                    reingested++;
                    out.printf("  ~ %s (%d ms)", d.fileName(), millis);
                }
            } catch (IngestionException | RuntimeException e) {
                out.println("  ! " + document.fileName() + ": " + e.getMessage() + " (previous version kept)");
            }
        }
        return reingested;
    }

    // ================================================================== search & selection

    /** Runs a BM25 query and publishes it to the view model (resets the selection to the first row). */
    public SearchResult search(String query, int top) {
        return search(query, top, SearchIndex.Filter.NONE);
    }

    /** Scoped variant ({@code find … --in / --type / --tag / --exclude}, {@code near}, {@code similar}). */
    public SearchResult search(String query, int top, SearchIndex.Filter filter) {
        SearchResult result = workbench.find(query, top, filter);
        view.publish(result);
        selectedRow = result.isEmpty() ? -1 : 0;
        return result;
    }

    // ================================================================== notes, clipboard, research helpers

    public ProjectNotes notes() {
        return notes;
    }

    /** Installed by a UI so terminal commands such as {@code cite} can fill the system clipboard. */
    public void setClipboard(ClipboardPort clipboard) {
        this.clipboard = clipboard;
    }

    public Optional<ClipboardPort> clipboard() {
        return Optional.ofNullable(clipboard);
    }

    /** Remembers a document access for {@code recent} (preview, open and reveal all count). */
    public void noteRecent(Path path, String action) {
        if (path != null) {
            notes.noteRecent(path, action, java.time.Instant.now());
        }
    }

    /** PDFs refused in this session because none of their pages has a text layer. */
    public Set<Path> scannedOnlyRefusals() {
        return Set.copyOf(scannedOnly);
    }

    /** Modification time of the source when it was indexed, or -1. */
    public long sourceModifiedMillis(String sha256) {
        IngestInfo info = ingestInfo.get(sha256);
        return info == null ? -1 : info.sourceModified();
    }

    /** Arguments of the last {@code search}/{@code find} command, for {@code saved --add}. */
    public Optional<String> lastSearchLine() {
        return Optional.ofNullable(lastSearchLine);
    }

    public void select(int row) {
        selectedRow = row;
    }

    public int selectedRow() {
        return selectedRow;
    }

    /** Source path of a result row, if the row exists and its document is still indexed. */
    public Optional<Path> sourceOf(int row) {
        return view.hit(row).flatMap(hit -> workbench.index().document(hit.docId())).map(DocumentRecord::source);
    }

    // ================================================================== OS actions

    public CompletableFuture<ShellResult> openRow(int row) {
        return sourceOf(row).map(p -> shellAction(OsShellBridge.Action.OPEN, p))
                .orElseGet(() -> noRow(OsShellBridge.Action.OPEN, row));
    }

    public CompletableFuture<ShellResult> revealRow(int row) {
        return sourceOf(row).map(p -> shellAction(OsShellBridge.Action.REVEAL, p))
                .orElseGet(() -> noRow(OsShellBridge.Action.REVEAL, row));
    }

    /** Every OS action goes through here so it is observable (audit trail of file access attempts). */
    private CompletableFuture<ShellResult> shellAction(OsShellBridge.Action action, Path path) {
        CompletableFuture<ShellResult> future = action == OsShellBridge.Action.OPEN
                ? shell.open(path) : shell.reveal(path);
        return future.thenApply(result -> {
            emit(new WorkbenchEvent.FileAction(result));
            if (result.ok() && result.path() != null && Files.isRegularFile(result.path())) {
                noteRecent(result.path(), action == OsShellBridge.Action.OPEN ? "open" : "reveal");
            }
            return result;
        });
    }

    private static CompletableFuture<ShellResult> noRow(OsShellBridge.Action action, int row) {
        return CompletableFuture.completedFuture(new ShellResult.Rejected(action, null,
                row < 0 ? "nothing selected" : "no result row " + (row + 1)));
    }

    /** Opens any path (e.g. a file-tree document) with its default application; audited like row actions. */
    public CompletableFuture<ShellResult> openPath(Path path) {
        return shellAction(OsShellBridge.Action.OPEN, path);
    }

    public CompletableFuture<ShellResult> revealPath(Path path) {
        return shellAction(OsShellBridge.Action.REVEAL, path);
    }

    // ================================================================== document actions (untrack, purge, clipboard)

    /** The indexed document behind a result row, if the row exists and the document is still indexed. */
    public Optional<DocumentRecord> documentAt(int row) {
        return view.hit(row).flatMap(hit -> workbench.index().document(hit.docId()));
    }

    public Optional<DocumentRecord> document(String sha256) {
        return sha256 == null ? Optional.empty() : workbench.index().document(sha256);
    }

    /**
     * Removes a document from the index only; the source file is never touched. The change is persisted with the
     * next snapshot write (the chassis requests one right away).
     */
    public RemovalResult untrack(String sha256) {
        Optional<DocumentRecord> document = document(sha256);
        if (document.isEmpty()) {
            return new RemovalResult.Rejected(sha256, "belge indekste değil");
        }
        if (document.get().isSystemDocument()) {
            return new RemovalResult.Rejected(sha256, "sistem örnek belgesi kaldırılamaz");
        }
        if (!remove(sha256)) {
            return new RemovalResult.Rejected(sha256, "belge zaten kaldırılmış");
        }
        notes.forget(sha256);
        emit(new WorkbenchEvent.Removed(document.get(), false));
        return new RemovalResult.Untracked(document.get());
    }

    /**
     * {@link #untrack} for a multi-selection, one result per distinct hash in input order. The hashes are copied
     * first, so callers may pass a live selection or {@code documents()} view without risking a
     * {@link java.util.ConcurrentModificationException}.
     */
    public List<RemovalResult> untrackAll(java.util.Collection<String> sha256s) {
        List<RemovalResult> results = new ArrayList<>();
        for (String sha256 : new java.util.LinkedHashSet<>(sha256s)) {
            results.add(untrack(sha256));
        }
        return results;
    }

    /**
     * Cheap safety checks for deleting a document's source file, suitable for the FX thread (a few {@code stat}
     * calls, no reading). {@link #purge} repeats them and additionally verifies the file content.
     *
     * @return why the file must not be deleted, or empty when a confirmation may be offered
     */
    public Optional<String> purgeRefusal(DocumentRecord document) {
        if (document == null) {
            return Optional.of("belge indekste değil");
        }
        if (document.isSystemDocument()) {
            return Optional.of("sistem örnek belgesi silinemez");
        }
        if (!document.local()) {
            return Optional.of("eş bilgisayardan gelen belgenin yerel bir kaynağı yok");
        }
        Path source;
        try {
            source = document.source().toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return Optional.of("geçersiz yol");
        }
        if (source.startsWith(config.dataDir().toAbsolutePath().normalize())) {
            return Optional.of("çalışma alanının kendi dosyaları silinemez");
        }
        if (Files.isSymbolicLink(source)) {
            return Optional.of("sembolik bağlantılar silinmez");
        }
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.of("dosya diskte bulunamadı (İndeksten Kaldır kullanın)");
        }
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.of("yalnızca normal dosyalar silinebilir");
        }
        return Optional.empty();
    }

    /**
     * Deletes a document's source file from disk, then removes every index entry pointing at that file. Blocking
     * (hashes the file): call it off the FX thread, after the user confirmed.
     *
     * <p>Safety: besides {@link #purgeRefusal}, the file must still hold exactly the bytes that were indexed (size and
     * SHA-256). A file edited or replaced since indexing is refused, so the user never deletes content they have not
     * seen. The index is only changed after the file system confirmed the delete.</p>
     */
    public RemovalResult purge(String sha256) {
        Optional<DocumentRecord> found = document(sha256);
        if (found.isEmpty()) {
            return new RemovalResult.Rejected(sha256, "belge indekste değil");
        }
        DocumentRecord document = found.get();
        Optional<String> refusal = purgeRefusal(document);
        if (refusal.isPresent()) {
            return new RemovalResult.Rejected(sha256, refusal.get());
        }
        Path source = document.source().toAbsolutePath().normalize();
        try {
            if (Files.size(source) != document.sizeBytes() || !Hashing.sha256Hex(source).equals(document.sha256())) {
                return new RemovalResult.Rejected(sha256,
                        "dosya indekslendikten sonra değişmiş; silinmedi (önce yeniden indeksleyin)");
            }
            Files.delete(source);
        } catch (NoSuchFileException e) {
            return new RemovalResult.Rejected(sha256, "dosya diskte bulunamadı");
        } catch (IOException | SecurityException e) {
            return new RemovalResult.Failed(document, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        int entries = 0;
        for (DocumentRecord d : workbench.documents()) {
            if (d.local() && d.source().toAbsolutePath().normalize().equals(source) && remove(d.sha256())) {
                entries++;
                notes.forget(d.sha256());
                emit(new WorkbenchEvent.Removed(d, true));
            }
        }
        return new RemovalResult.Purged(document, source, entries);
    }

    /** Copies the absolute source path; returns the text placed on the clipboard. */
    public String copyPath(DocumentRecord document, ClipboardPort clipboard) {
        String path = document.source().toAbsolutePath().normalize().toString();
        clipboard.putText(path);
        return path;
    }

    /**
     * Copies the extracted, normalized text (the concatenated chunks, capped at {@link #MAX_COPY_CHARS}); returns
     * the number of characters placed on the clipboard.
     */
    public int copyContent(DocumentRecord document, ClipboardPort clipboard) {
        String text = contentText(document, MAX_COPY_CHARS);
        clipboard.putText(text);
        return text.length();
    }

    /** Chunks concatenate to the normalized document; only the first {@code maxChars} characters are kept. */
    public static String contentText(DocumentRecord document, int maxChars) {
        long total = 0;
        for (TextChunk c : document.chunks()) {
            total += c.text().length();
        }
        StringBuilder sb = new StringBuilder((int) Math.min(total, maxChars));
        for (TextChunk c : document.chunks()) {
            int room = maxChars - sb.length();
            if (room <= 0) {
                break;
            }
            sb.append(c.text(), 0, Math.min(room, c.text().length()));
        }
        return sb.toString();
    }

    // ================================================================== key bindings

    /** Handles a key event from any surface; returns {@code true} if a bound action consumed it. */
    public boolean dispatch(KeyStroke stroke) {
        Optional<Action> action = keys.actionFor(stroke);
        action.ifPresent(this::perform);
        return action.isPresent();
    }

    public void perform(Action action) {
        Runnable override;
        synchronized (actionOverrides) {
            override = actionOverrides.get(action);
        }
        if (override != null) {
            override.run();
            return;
        }
        switch (action) {
            case FOCUS_SEARCH -> hooks.focusSearch();
            case TOGGLE_TERMINAL -> panels.toggle(Panel.TERMINAL);
            case TOGGLE_FILE_TREE -> panels.toggle(Panel.FILE_TREE);
            case TOGGLE_MODE -> view.toggle();
            case OPEN_SELECTED -> openRow(selectedRow).thenAccept(this::reportShell);
            case SHOW_IN_EXPLORER -> revealRow(selectedRow).thenAccept(this::reportShell);
            case SAVE_INDEX -> saveAsync();
            case CLEAR_TERMINAL -> terminal.buffer().clear();
            // Presentation-only actions: the chassis installs the real handler with overrideAction.
            case TOGGLE_FOCUS, PREVIEW_SELECTED, ADD_FILES ->
                    terminal.output().println(action + ": no handler installed");
        }
    }

    private void reportShell(ShellResult result) {
        if (!result.ok()) {
            terminal.output().println(result.describe());
        }
    }

    // ================================================================== terminal commands

    private void registerCommands() {
        Set<String> searchOptions = new java.util.HashSet<>(ResearchCommands.SCOPE_OPTIONS);
        searchOptions.add("top");
        terminal.register(new CommandSpec("search",
                "search <query> [--top N] [--in type|folder] [--type T] [--since/--until yyyy-mm-dd] [--tag L]"
                        + " [--pinned] [--exclude w1,w2]",
                "BM25 search ('-word' excludes); rows can then be opened with 'open N' / 'explore N'",
                searchOptions, this::cmdSearch));
        terminal.register(new CommandSpec("index",
                "index --rebuild | --save | --load | --verify | --list | --add <path> | --remove <id>",
                "Manage the index and its on-disk snapshot", Set.of("add", "remove"), this::cmdIndex));
        terminal.register(new CommandSpec("stats", "stats [--memory] [--index]", "JVM memory and index statistics",
                Set.of(), this::cmdStats));
        terminal.register(new CommandSpec("open", "open <row|path|id>", "Open with the default application",
                Set.of(), (inv, out) -> cmdShell(inv, out, OsShellBridge.Action.OPEN)));
        terminal.register(new CommandSpec("explore", "explore <row|path|id>", "Show in the file manager",
                Set.of(), (inv, out) -> cmdShell(inv, out, OsShellBridge.Action.REVEAL)));
        terminal.register(new CommandSpec("mode", "mode [--toggle | simple | detailed]",
                "Show or switch the result view mode", Set.of(), this::cmdMode));
        terminal.register(new CommandSpec("set-key", "set-key <ACTION> <stroke|none> [--force]",
                "Rebind a shortcut and save keybindings.properties", Set.of(), this::cmdSetKey));
        terminal.register(new CommandSpec("keys", "keys [--reload | --reset]", "List, reload or reset shortcuts",
                Set.of(), this::cmdKeys));
        terminal.register(new CommandSpec("panel", "panel <tree|results|terminal> [--show|--hide|--toggle|--detach]",
                "Show or change panel visibility", Set.of(), this::cmdPanel));
        terminal.register(new CommandSpec("docs", "docs", "List every indexed document", Set.of(), this::cmdDocs));
        terminal.register(new CommandSpec("remove", "remove <name|id>",
                "Remove a document from the index; the file on disk is never touched", Set.of(), this::cmdRemove));
        terminal.alias("find", "search");
        terminal.alias("reveal", "explore");
        ResearchCommands.register(this, terminal);
    }

    /** Project folder holding the snapshot, key bindings and notes. */
    public Path dataDir() {
        return config.dataDir();
    }

    private void cmdDocs(Invocation inv, Output out) {
        List<DocumentRecord> documents = workbench.documents();
        for (DocumentRecord d : documents) {
            out.printf("  %s  %-5s %6d chunks  %s", d.shortId(), d.type().extension(), d.chunks().size(),
                    d.fileName());
        }
        out.println(documents.size() + " document(s)");
    }

    private void cmdRemove(Invocation inv, Output out) {
        String target = inv.joinedArgs().strip();
        if (target.isEmpty()) {
            throw new IllegalArgumentException("usage: remove <name|id>");
        }
        Optional<DocumentRecord> byId = workbench.resolve(target);
        List<DocumentRecord> matches = byId.map(List::of).orElseGet(() -> documentsNamed(target));
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("no indexed document matches: " + target);
        }
        if (matches.size() > 1) {
            matches.forEach(d -> out.println("  " + d.shortId() + "  " + d.fileName()));
            throw new IllegalArgumentException(matches.size() + " documents match '" + target
                    + "'; use a longer name or the id");
        }
        RemovalResult result = untrack(matches.getFirst().sha256());
        if (!result.ok()) {
            throw new IllegalArgumentException(result.describe());
        }
        out.println(result.describe());
    }

    /**
     * Indexed documents whose file name equals {@code name} (with or without extension, ignoring case); failing
     * that, those whose file name contains it.
     */
    List<DocumentRecord> documentsNamed(String name) {
        String wanted = name.strip().toLowerCase(Locale.ROOT);
        if (wanted.isEmpty()) {
            return List.of();
        }
        List<DocumentRecord> all = workbench.documents();
        List<DocumentRecord> exact = all.stream().filter(d -> {
            String file = d.fileName().toLowerCase(Locale.ROOT);
            int dot = file.lastIndexOf('.');
            return file.equals(wanted) || (dot > 0 && file.substring(0, dot).equals(wanted));
        }).toList();
        return exact.isEmpty()
                ? all.stream().filter(d -> d.fileName().toLowerCase(Locale.ROOT).contains(wanted)).toList()
                : exact;
    }

    private void cmdSearch(Invocation inv, Output out) {
        String query = inv.joinedArgs();
        if (query.isBlank()) {
            throw new IllegalArgumentException("usage: search [--top N] <query> [--in X] [--type T] [--since D]"
                    + " [--until D] [--tag L] [--pinned] [--exclude W]   (-word in the query also excludes)");
        }
        SearchIndex.Filter filter = ResearchCommands.filter(this, inv);
        SearchResult result = search(query, inv.intOption("top", config.defaultTop(), 1, 200), filter);
        String raw = inv.raw().strip();
        int space = raw.indexOf(' ');
        lastSearchLine = space < 0 ? null : raw.substring(space + 1).strip();
        String scope = ResearchCommands.describeScope(inv);
        if (!scope.isEmpty()) {
            out.println("scope: " + scope);
        }
        printResults(result, out);
    }

    /** The result table as {@code search} prints it; the rows are those of the view model. */
    void printResults(SearchResult result, Output out) {
        out.printf("%d hit(s) of %d matching chunk(s) in %.2f ms  [%s]", result.hits().size(),
                result.matchedChunks(), result.elapsedMillis(), view.mode());
        for (int row = 0; row < view.size(); row++) {
            StringBuilder sb = out.scratch();
            view.render(row, sb);
            out.println(sb);
        }
        if (view.mode() == ViewMode.DETAILED) {
            out.println("jvm: " + MemorySnapshot.now().describe());
        }
    }

    private void cmdIndex(Invocation inv, Output out) throws IOException {
        if (inv.has("rebuild")) {
            long t0 = System.nanoTime();
            int n = rebuild(out);
            out.printf("rebuilt %d document(s) in %.0f ms", n, (System.nanoTime() - t0) / 1e6);
        } else if (inv.has("save")) {
            SaveReport r = save();
            out.printf("saved %d document(s), %d chunk(s), %d KB to %s in %.1f ms", r.documents(), r.chunks(),
                    r.bytes() >> 10, r.file(), r.millis());
        } else if (inv.has("load")) {
            // Same rules as the startup warm-up: streamed, already-indexed documents skipped, heap ceiling honoured.
            HeapGuard guard = new HeapGuard(HeapGuard.DEFAULT_CEILING);
            int[] counts = new int[3]; // added, seen, skipped for memory
            LoadReport r = storage.load(new IndexStorageEngine.Visitor() {
                @Override
                public boolean wants(MetadataRecord meta) {
                    counts[1]++;
                    if (workbench.index().contains(meta.sha256())) {
                        return false;
                    }
                    if (!guard.hasHeadroom(estimatedIndexBytes(meta.charCount()))) {
                        counts[2]++;
                        return false;
                    }
                    return true;
                }

                @Override
                public void entry(Entry e) {
                    if (workbench.index().add(e.document())) {
                        unwarmed.remove(e.metadata().sha256());
                        ingestInfo.put(e.metadata().sha256(),
                                new IngestInfo(e.metadata().parseMillis(), e.metadata().sourceModifiedMillis()));
                        counts[0]++;
                    }
                }
            });
            r.problems().forEach(p -> out.println("rejected: " + p));
            out.printf("loaded %s (%s): %d new document(s) of %d in %.1f ms%s", storage.file().getFileName(),
                    r.origin(), counts[0], counts[1], r.millis(),
                    counts[2] > 0 ? ", " + counts[2] + " left out (memory ceiling)" : "");
        } else if (inv.options().containsKey("add")) {
            int n = addPath(Path.of(inv.option("add", "")).toAbsolutePath().normalize(), out);
            out.println(n + " document(s) added");
        } else if (inv.options().containsKey("remove")) {
            DocumentRecord d = workbench.resolve(inv.option("remove", ""))
                    .orElseThrow(() -> new IllegalArgumentException("no unique document for that id"));
            RemovalResult result = untrack(d.sha256()); // audited like every other removal
            if (!result.ok()) {
                throw new IllegalArgumentException(result.describe());
            }
            out.println("removed " + d.fileName());
        } else if (inv.has("verify")) {
            boolean anyValid = false;
            for (IndexStorageEngine.FileCheck check : storage.verify()) {
                anyValid |= check.valid();
                out.printf("  %-15s %s  %s", check.origin(), !check.present() ? "absent"
                                : check.valid() ? String.format(Locale.ROOT, "OK · %d document(s) · %d KB · saved %s",
                                check.documents(), check.bytes() >> 10, check.savedAt()) : "DAMAGED · " + check.problem(),
                        check.file().getFileName());
            }
            out.println(anyValid ? "snapshot can be loaded" : "no loadable snapshot on disk; 'index --save' writes one");
        } else if (inv.has("list")) {
            for (DocumentRecord d : workbench.documents()) {
                out.printf("  %s  %-5s %6d chunks  %s", d.shortId(), d.type().extension(), d.chunks().size(),
                        d.fileName());
            }
            out.println(workbench.documents().size() + " document(s)");
        } else {
            throw new IllegalArgumentException("usage: index --rebuild | --save | --load | --list | --add <path>"
                    + " | --remove <id>");
        }
    }

    private void cmdStats(Invocation inv, Output out) {
        boolean all = !inv.has("memory") && !inv.has("index");
        if (all || inv.has("memory")) {
            MemorySnapshot m = MemorySnapshot.now();
            out.println("memory: " + m.describe());
            out.printf("threads: %d live (%d peak)", Thread.activeCount(),
                    java.lang.management.ManagementFactory.getThreadMXBean().getPeakThreadCount());
        }
        if (all || inv.has("index")) {
            SearchIndex.Stats s = workbench.stats();
            out.printf("index: %d documents, %d chunks, %d terms, %d postings, %d tokens", s.documents(), s.chunks(),
                    s.terms(), s.postings(), s.tokens());
            long bytes = sizeOrZero(storage.file());
            out.printf("snapshot: %s (%d KB)%s", storage.file(), bytes >> 10, dirty() ? ", unsaved changes" : "");
        }
    }

    private void cmdShell(Invocation inv, Output out, OsShellBridge.Action action)
            throws InterruptedException, ExecutionException {
        if (inv.args().isEmpty()) {
            throw new IllegalArgumentException("usage: " + (action == OsShellBridge.Action.OPEN ? "open" : "explore")
                    + " <row|path|id>");
        }
        String target = inv.joinedArgs();
        Path path = resolveTarget(target)
                .orElseThrow(() -> new IllegalArgumentException("no result row, file, document name or id: " + target));
        CompletableFuture<ShellResult> future = shellAction(action, path);
        try {
            out.println(future.get(SHELL_WAIT_SECONDS, TimeUnit.SECONDS).describe());
        } catch (TimeoutException e) {
            out.println(action + " still pending for " + path.getFileName());
        }
    }

    /**
     * A 1-based result row, an indexed document id (prefix), a unique indexed file name, a registered binary asset
     * (id prefix or name, see {@link #resolveAsset}), or a file path.
     */
    public Optional<Path> resolveTarget(String target) {
        if (target.chars().allMatch(Character::isDigit) && target.length() <= 4) {
            int row = Integer.parseInt(target) - 1;
            Optional<Path> source = sourceOf(row);
            if (source.isPresent()) {
                select(row);
                return source;
            }
        }
        Optional<DocumentRecord> byId = workbench.resolve(target);
        if (byId.isPresent()) {
            return byId.map(DocumentRecord::source);
        }
        List<DocumentRecord> byName = documentsNamed(target);
        if (byName.size() == 1) {
            return Optional.of(byName.getFirst().source());
        }
        if (byName.isEmpty()) {
            // Not a document: videos, photos and archives live in the content store, not the text index.
            Optional<BinaryAsset> asset = resolveAsset(target);
            if (asset.isPresent()) {
                return asset.map(BinaryAsset::source);
            }
        }
        try {
            Path path = Path.of(target).toAbsolutePath().normalize();
            return Files.exists(path) ? Optional.of(path) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private void cmdMode(Invocation inv, Output out) {
        if (inv.has("toggle")) {
            view.toggle();
        } else if (!inv.args().isEmpty()) {
            String arg = inv.args().getFirst().toLowerCase(Locale.ROOT);
            view.setMode(switch (arg) {
                case "simple", "s" -> ViewMode.SIMPLE;
                case "detailed", "detail", "it", "d" -> ViewMode.DETAILED;
                default -> throw new IllegalArgumentException("unknown mode '" + arg + "' (simple | detailed)");
            });
        }
        out.println("mode: " + view.mode().name().toLowerCase(Locale.ROOT));
    }

    private void cmdSetKey(Invocation inv, Output out) throws IOException {
        if (inv.args().size() < 2) {
            throw new IllegalArgumentException("usage: set-key <ACTION> <stroke|none> [--force]");
        }
        Action action = Action.parse(inv.args().getFirst()).orElseThrow(() -> new IllegalArgumentException(
                "unknown action; one of " + java.util.Arrays.toString(Action.values())));
        String spec = String.join(" ", inv.args().subList(1, inv.args().size()));
        switch (keys.bind(action, spec, inv.has("force"))) {
            case BindResult.Bound(Action a, KeyStroke s, Optional<Action> displaced) -> {
                keys.save();
                out.println(a + " = " + (s == null ? "none" : s)
                        + displaced.map(d -> "  (" + d + " is now " + keys.strokeFor(d).map(Object::toString)
                        .orElse("unbound") + ")").orElse(""));
            }
            case BindResult.Conflict(Action a, KeyStroke s, Action holder) ->
                    throw new IllegalArgumentException(s + " is already bound to " + holder
                            + "; use --force to reassign");
            case BindResult.Invalid(Action a, String raw, String reason) ->
                    throw new IllegalArgumentException("'" + raw + "': " + reason);
        }
    }

    private void cmdKeys(Invocation inv, Output out) throws IOException {
        if (inv.has("reload")) {
            List<KeyMapRegistry.Issue> issues = keys.load();
            issues.forEach(i -> out.println("  ! " + i));
            out.println("reloaded " + keys.file());
        } else if (inv.has("reset")) {
            keys.resetDefaults();
            keys.save();
            out.println("defaults restored");
        }
        for (Action a : Action.values()) {
            out.printf("  %-18s %s", a, keys.strokeFor(a).map(Object::toString).orElse("(unbound)"));
        }
    }

    private void cmdPanel(Invocation inv, Output out) {
        if (inv.args().isEmpty()) {
            panels.snapshot().values().stream()
                    .sorted(java.util.Comparator.comparing(PanelStateCoordinator.PanelState::panel))
                    .forEach(s -> out.printf("  %-9s %s", s.panel().alias(), s.visibility()));
            return;
        }
        Panel panel = Panel.parse(inv.args().getFirst())
                .orElseThrow(() -> new IllegalArgumentException("unknown panel (tree | results | terminal)"));
        boolean changed;
        if (inv.has("show")) {
            changed = panels.show(panel);
        } else if (inv.has("hide")) {
            changed = panels.hide(panel);
        } else if (inv.has("detach")) {
            changed = panels.detach(panel);
        } else {
            changed = panels.toggle(panel);
        }
        out.println(panel.alias() + ": " + panels.state(panel).visibility() + (changed ? "" : " (unchanged)"));
    }

    // ================================================================== helpers

    /** Order-independent fingerprint of the indexed set, including display names. */
    private static long fingerprint(List<DocumentRecord> documents) {
        long h = documents.size();
        for (DocumentRecord d : documents) {
            long x = d.sha256().hashCode() * 0x9E3779B97F4A7C15L + d.fileName().hashCode();
            x ^= x >>> 31;
            h += x * 0xBF58476D1CE4E5B9L;
        }
        return h == 0 ? 1 : h;
    }

    /** Same heuristic as the ingestion path: ~40 bytes of postings per token, one token per six characters. */
    private static long estimatedIndexBytes(long chars) {
        return Math.max(1 << 20, chars / 6 * 40);
    }

    private static long sizeOrZero(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    /** Bridges the view model's telemetry needs to the live index without exposing the index to it. */
    private final class IndexTelemetry implements ViewModeCoordinator.TelemetrySource {
        @Override
        public Optional<TextChunk> chunk(String docId, int chunkIndex) {
            return workbench.index().document(docId)
                    .filter(d -> chunkIndex >= 0 && chunkIndex < d.chunks().size())
                    .map(d -> d.chunks().get(chunkIndex));
        }

        @Override
        public String normalize(String surface) {
            return tokenizer.normalizeTerm(surface);
        }

        @Override
        public long parseMillis(String docId) {
            IngestInfo info = ingestInfo.get(docId);
            return info == null ? -1 : info.parseMillis();
        }
    }

    /** Convenience for UIs: the hit behind the current selection. */
    public Optional<SearchHit> selectedHit() {
        return view.hit(selectedRow);
    }
}
