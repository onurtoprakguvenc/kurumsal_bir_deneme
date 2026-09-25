package org.example.project;

import org.example.core.IngestionException;
import org.example.model.DocumentRecord;
import org.example.state.Subscription;
import org.example.storage.IndexStorageEngine.SaveReport;
import org.example.watcher.FileWatcherService;
import org.example.workbench.WorkbenchController;
import org.example.workbench.WorkbenchController.StartupReport;
import org.example.workbench.WorkbenchController.WorkbenchEvent;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Owns the set of projects in a workspace home and exactly one active project at a time.
 *
 * <h2>Isolation</h2>
 * Each active project gets its own {@link WorkbenchController} (and therefore its own in-memory index, terminal,
 * view state, snapshot file and key bindings — all inside the project folder) plus its own
 * {@link FileWatcherService} that watches only that project's document sources. Nothing indexed in one project is
 * reachable from another.
 *
 * <h2>Atomic switching</h2>
 * {@link #switchTo(ProjectProfile)} runs in three phases under one lock:
 * <ol>
 *   <li><b>Unload</b> — listeners detach (UI, focus mode, preview), the active index is saved and its manifest
 *       written. If saving fails the switch is cancelled and the old project stays active and attached.</li>
 *   <li><b>Flush</b> — watcher and controller are closed and every reference to the old index is dropped;
 *       optionally a GC is requested so the heap is back at baseline before the next warm-up (two indexes are never
 *       resident at once, which matters under a 512 MB heap).</li>
 *   <li><b>Warm</b> — a new controller warms the target snapshot. If that fails, the previous project is re-opened
 *       from the snapshot just saved and a {@link SwitchException} reports the rollback.</li>
 * </ol>
 * No JVM restart is involved; the UI rebinds to the new controller from an {@link #onActivated} callback.
 */
public final class ProjectWorkspaceManager implements AutoCloseable {

    /** Builds the controller for a project (index, parser, shell and quota are the caller's choice). */
    @FunctionalInterface
    public interface ControllerFactory {
        WorkbenchController create(ProjectProfile profile);
    }

    public record ActiveProject(ProjectProfile profile, WorkbenchController controller, Instant activatedAt) {
    }

    /**
     * @param from               project that was active before, or {@code null}
     * @param saved              snapshot written while unloading, or {@code null} when nothing needed saving
     * @param heapBeforeBytes    used heap before the switch
     * @param heapAfterUnloadBytes used heap after the old project was released
     * @param heapAfterWarmBytes used heap once the target was warm
     */
    public record SwitchReport(ProjectProfile from, ProjectProfile to, SaveReport saved, StartupReport startup,
                               long unloadNanos, long warmNanos, long heapBeforeBytes, long heapAfterUnloadBytes,
                               long heapAfterWarmBytes) {
        public String describe() {
            return String.format(java.util.Locale.ROOT,
                    "project '%s' active: %d documents warmed in %.0f ms (unload %.0f ms); heap %d -> %d -> %d MB",
                    to.name(), startup == null ? 0 : startup.warmed(), warmNanos / 1e6, unloadNanos / 1e6,
                    heapBeforeBytes >> 20, heapAfterUnloadBytes >> 20, heapAfterWarmBytes >> 20);
        }
    }

    /** A switch did not complete; {@code rolledBack} tells whether the previous project is active again. */
    public static final class SwitchException extends Exception {
        private final boolean rolledBack;

        public SwitchException(String message, boolean rolledBack, Throwable cause) {
            super(message, cause);
            this.rolledBack = rolledBack;
        }

        public boolean rolledBack() {
            return rolledBack;
        }
    }

    private static final String RECENT_FILE = "recent-projects.txt";
    private static final int MAX_RECENT = 12;

    private final Path home;
    private final Path projectsDir;
    private final ControllerFactory factory;
    private final boolean gcOnSwitch;
    private final Duration watchDebounce;
    private final ReentrantLock switchLock = new ReentrantLock();
    private final CopyOnWriteArrayList<Consumer<ActiveProject>> activatedListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<ActiveProject>> deactivatingListeners = new CopyOnWriteArrayList<>();

    private volatile ActiveProject active;
    private FileWatcherService watcher;
    private Subscription watchSubscription;

    public ProjectWorkspaceManager(Path home, ControllerFactory factory) {
        this(home, factory, true, FileWatcherService.DEFAULT_DEBOUNCE);
    }

    /**
     * @param gcOnSwitch    request a collection between unload and warm-up
     * @param watchDebounce quiet period before a changed source is re-indexed
     */
    public ProjectWorkspaceManager(Path home, ControllerFactory factory, boolean gcOnSwitch, Duration watchDebounce) {
        this.home = Objects.requireNonNull(home, "home must not be null").toAbsolutePath().normalize();
        this.projectsDir = this.home.resolve("projects");
        this.factory = Objects.requireNonNull(factory, "factory must not be null");
        this.gcOnSwitch = gcOnSwitch;
        this.watchDebounce = Objects.requireNonNull(watchDebounce);
    }

    public Path home() {
        return home;
    }

    public Optional<ActiveProject> active() {
        return Optional.ofNullable(active);
    }

    /** Called after a project became active (on the switching thread): bind UI, commands, policies here. */
    public Subscription onActivated(Consumer<ActiveProject> listener) {
        activatedListeners.add(Objects.requireNonNull(listener));
        return () -> activatedListeners.remove(listener);
    }

    /** Called before the active project is unloaded: release everything that references its controller. */
    public Subscription onDeactivating(Consumer<ActiveProject> listener) {
        deactivatingListeners.add(Objects.requireNonNull(listener));
        return () -> deactivatingListeners.remove(listener);
    }

    // ================================================================== catalogue

    /**
     * Creates a project. Without {@code folder} it goes to {@code <home>/projects/<slug>} (suffixed if taken). When a
     * project is active its key bindings are copied, so custom shortcuts carry over until changed.
     */
    public ProjectProfile create(String name, Path folder) throws IOException {
        Path root = folder != null ? folder.toAbsolutePath().normalize() : uniqueFolder(ProjectProfile.slug(name));
        if (Files.exists(root.resolve(ProjectProfile.PROJECT_FILE))) {
            throw new IOException("a project already exists in " + root);
        }
        ProjectProfile profile = ProjectProfile.create(name, root);
        profile.write();
        ActiveProject current = active;
        if (current != null && Files.isRegularFile(current.profile().keyBindingsFile())) {
            Files.copy(current.profile().keyBindingsFile(), profile.keyBindingsFile(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        remember(profile);
        return profile;
    }

    private Path uniqueFolder(String slug) {
        Path candidate = projectsDir.resolve(slug);
        for (int i = 2; Files.exists(candidate); i++) {
            candidate = projectsDir.resolve(slug + "-" + i);
        }
        return candidate;
    }

    /** Projects under {@code <home>/projects} plus recently opened external project folders, without duplicates. */
    public List<ProjectProfile> list() {
        Map<String, ProjectProfile> byId = new LinkedHashMap<>();
        for (Path folder : recentFolders()) {
            tryRead(folder).ifPresent(p -> byId.putIfAbsent(p.id(), p));
        }
        if (Files.isDirectory(projectsDir)) {
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(projectsDir, Files::isDirectory)) {
                for (Path dir : dirs) {
                    tryRead(dir).ifPresent(p -> byId.putIfAbsent(p.id(), p));
                }
            } catch (IOException ignored) {
                // an unreadable projects folder just lists nothing
            }
        }
        return List.copyOf(byId.values());
    }

    /** Resolves a project by id, name, slug, or a path to its folder / {@code .dwbproj} file. */
    public Optional<ProjectProfile> find(String nameIdOrPath) {
        for (ProjectProfile p : list()) {
            if (p.matches(nameIdOrPath)) {
                return Optional.of(p);
            }
        }
        try {
            Path path = Path.of(nameIdOrPath);
            if (Files.exists(path)) {
                return tryRead(path);
            }
        } catch (RuntimeException ignored) {
            // not a path
        }
        return Optional.empty();
    }

    private static Optional<ProjectProfile> tryRead(Path fileOrFolder) {
        try {
            return Optional.of(ProjectProfile.read(fileOrFolder));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private List<Path> recentFolders() {
        try {
            return Files.readAllLines(home.resolve(RECENT_FILE), StandardCharsets.UTF_8).stream()
                    .filter(l -> !l.isBlank()).map(String::strip).map(Path::of).toList();
        } catch (NoSuchFileException e) {
            return List.of();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private void remember(ProjectProfile profile) throws IOException {
        List<Path> recent = new ArrayList<>(recentFolders());
        recent.remove(profile.root());
        recent.addFirst(profile.root());
        Files.createDirectories(home);
        List<String> lines = recent.stream().limit(MAX_RECENT).map(Path::toString).toList();
        writeAtomically(home.resolve(RECENT_FILE), String.join("\n", lines) + "\n");
    }

    /** The most recently opened project that still exists. */
    public Optional<ProjectProfile> mostRecent() {
        for (Path folder : recentFolders()) {
            Optional<ProjectProfile> p = tryRead(folder);
            if (p.isPresent()) {
                return p;
            }
        }
        return Optional.empty();
    }

    // ================================================================== switching

    /** Switches on a fresh virtual thread (so callers such as a closing terminal thread are never interrupted mid-way). */
    public CompletableFuture<SwitchReport> switchAsync(ProjectProfile target) {
        CompletableFuture<SwitchReport> future = new CompletableFuture<>();
        Thread.ofVirtual().name("dwb-project-switch").start(() -> {
            try {
                future.complete(switchTo(target));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    public SwitchReport switchTo(ProjectProfile target) throws SwitchException {
        Objects.requireNonNull(target, "target must not be null");
        switchLock.lock();
        try {
            ActiveProject previous = active;
            if (previous != null && previous.profile().id().equals(target.id())) {
                return new SwitchReport(previous.profile(), previous.profile(), null, null, 0, 0, usedHeap(),
                        usedHeap(), usedHeap());
            }
            long heapBefore = usedHeap();
            long t0 = System.nanoTime();
            SaveReport saved = null;
            if (previous != null) {
                deactivating(previous);
                try {
                    saved = saveAndManifest(previous);
                } catch (IOException e) {
                    activated(previous);
                    throw new SwitchException("could not save '" + previous.profile().name() + "' (" + e.getMessage()
                            + "); switch cancelled, it stays active", true, e);
                }
                unload(previous);
            }
            long unloadNanos = System.nanoTime() - t0;
            long heapAfterUnload = usedHeap();

            long t1 = System.nanoTime();
            ActiveProject next;
            StartupReport startup;
            try {
                next = load(target);
                startup = lastStartup;
            } catch (IOException | RuntimeException e) {
                boolean restored = false;
                if (previous != null) {
                    try {
                        load(previous.profile());
                        restored = true;
                    } catch (IOException | RuntimeException rollbackFailure) {
                        e.addSuppressed(rollbackFailure);
                    }
                }
                throw new SwitchException("could not open '" + target.name() + "': " + e.getMessage()
                        + (restored ? "; '" + previous.profile().name() + "' re-opened" : ""), restored, e);
            }
            SwitchReport report = new SwitchReport(previous == null ? null : previous.profile(), target, saved,
                    startup, unloadNanos, System.nanoTime() - t1, heapBefore, heapAfterUnload, usedHeap());
            next.controller().terminal().output().println(report.describe());
            return report;
        } finally {
            switchLock.unlock();
        }
    }

    private StartupReport lastStartup;

    /** Creates, warms and activates a controller for {@code profile}; notifies listeners. */
    private ActiveProject load(ProjectProfile profile) throws IOException {
        if (!Files.isRegularFile(profile.projectFile())) {
            throw new NoSuchFileException(profile.projectFile().toString());
        }
        WorkbenchController controller = factory.create(profile);
        try {
            lastStartup = controller.startup();
            startWatcher(controller);
        } catch (IOException | RuntimeException e) {
            stopWatcher();
            controller.close();
            throw e;
        }
        ActiveProject next = new ActiveProject(profile, controller, Instant.now());
        active = next;
        try {
            remember(profile);
        } catch (IOException ignored) {
            // the recent list is a convenience
        }
        activated(next);
        return next;
    }

    private void unload(ActiveProject project) {
        stopWatcher();
        active = null;
        project.controller().close();
        if (gcOnSwitch) {
            System.gc();
        }
    }

    /** Saves the active project's index and manifest (when dirty). */
    public Optional<SaveReport> saveActive() throws IOException {
        ActiveProject current = active;
        return current == null ? Optional.empty() : Optional.ofNullable(saveAndManifest(current));
    }

    private SaveReport saveAndManifest(ActiveProject project) throws IOException {
        WorkbenchController c = project.controller();
        SaveReport report = c.dirty() || !Files.isRegularFile(project.profile().indexFile()) ? c.save() : null;
        writeManifest(project.profile(), c.workbench().documents());
        return report;
    }

    /** Tab-separated manifest: one line per document, readable without the application. */
    static void writeManifest(ProjectProfile profile, List<DocumentRecord> documents) throws IOException {
        StringBuilder sb = new StringBuilder(128 + documents.size() * 160);
        sb.append("# sha256\ttype\tbytes\tchunks\torigin\tingested\tname\tsource\n");
        for (DocumentRecord d : documents) {
            sb.append(d.sha256()).append('\t').append(d.type().extension()).append('\t').append(d.sizeBytes())
                    .append('\t').append(d.chunks().size()).append('\t').append(d.origin()).append('\t')
                    .append(d.ingestedAt()).append('\t').append(tsv(d.fileName())).append('\t')
                    .append(tsv(d.source().toString())).append('\n');
        }
        writeAtomically(profile.manifestFile(), sb.toString());
    }

    private static String tsv(String s) {
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static void writeAtomically(Path file, String content) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            w.write(content);
        }
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deactivating(ActiveProject project) {
        for (Consumer<ActiveProject> l : deactivatingListeners) {
            l.accept(project);
        }
    }

    private void activated(ActiveProject project) {
        for (Consumer<ActiveProject> l : activatedListeners) {
            l.accept(project);
        }
    }

    // ================================================================== project-scoped watcher

    /**
     * Watches the sources of the project's local documents; a modified source is re-indexed (old version removed
     * first, so the new one keeps its name), a deleted one is dropped. Newly ingested documents are added to the
     * watch set through the controller's {@code Ingested} events.
     */
    private void startWatcher(WorkbenchController controller) throws IOException {
        FileWatcherService w = new FileWatcherService(watchDebounce, new FileWatcherService.Listener() {
            @Override
            public void modified(Path file) {
                reindex(controller, file);
            }

            @Override
            public void deleted(Path file) {
                for (DocumentRecord d : documentsAt(controller, file)) {
                    controller.remove(d.sha256());
                    controller.terminal().output().println("watch: " + d.fileName() + " deleted; removed from index");
                }
            }
        });
        w.start();
        for (DocumentRecord d : controller.workbench().documents()) {
            if (d.local()) {
                watchQuietly(w, d.source());
            }
        }
        watcher = w;
        watchSubscription = controller.onEvent(event -> {
            if (event instanceof WorkbenchEvent.Ingested(DocumentRecord document, long millis) && document.local()) {
                watchQuietly(w, document.source());
            }
        });
    }

    private static void watchQuietly(FileWatcherService w, Path source) {
        try {
            w.watch(source);
        } catch (IOException | RuntimeException ignored) {
            // unwatchable folders (network drives without notifications) fall back to the startup freshness check
        }
    }

    private static List<DocumentRecord> documentsAt(WorkbenchController controller, Path file) {
        Path target = file.toAbsolutePath().normalize();
        return controller.workbench().documents().stream()
                .filter(d -> d.local() && d.source().toAbsolutePath().normalize().equals(target)).toList();
    }

    private static void reindex(WorkbenchController controller, Path file) {
        List<DocumentRecord> current = documentsAt(controller, file);
        if (current.isEmpty()) {
            return;
        }
        // The indexed version is replaced only after the new one was extracted, so a save in progress, a lock held
        // by the editing application or a transient read error never makes the document disappear.
        String previous = current.getFirst().sha256();
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                controller.reindex(previous, file);
                for (DocumentRecord d : current.subList(1, current.size())) {
                    controller.remove(d.sha256());
                }
                controller.terminal().output().println("watch: re-indexed " + file.getFileName());
                return;
            } catch (IngestionException e) {
                if (e.reason() != IngestionException.Reason.CHANGED_DURING_READ || attempt == 2) {
                    controller.terminal().output().println("watch: could not re-index " + file.getFileName() + ": "
                            + e.getMessage() + " (previous version kept)");
                    return;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void stopWatcher() {
        if (watchSubscription != null) {
            watchSubscription.close();
            watchSubscription = null;
        }
        if (watcher != null) {
            watcher.close();
            watcher = null;
        }
    }

    /** Number of source files the active project's watcher covers. */
    public int watchedFiles() {
        FileWatcherService w = watcher;
        return w == null ? 0 : w.watched().size();
    }

    /** Every source file the active project's watcher follows (empty when no project is active). */
    public java.util.Set<Path> watchedPaths() {
        FileWatcherService w = watcher;
        return w == null ? java.util.Set.of() : w.watched();
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    // ================================================================== shutdown

    /** Saves and unloads the active project (if any). */
    public void closeActive() throws IOException {
        switchLock.lock();
        try {
            ActiveProject current = active;
            if (current == null) {
                return;
            }
            deactivating(current);
            try {
                saveAndManifest(current);
            } finally {
                stopWatcher();
                active = null;
                current.controller().close();
            }
        } finally {
            switchLock.unlock();
        }
    }

    @Override
    public void close() {
        try {
            closeActive();
        } catch (IOException e) {
            System.getLogger(ProjectWorkspaceManager.class.getName())
                    .log(System.Logger.Level.WARNING, "Active project not saved on shutdown", e);
        }
    }
}
