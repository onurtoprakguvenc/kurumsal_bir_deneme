package org.example.watcher;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * Background file-system monitor for the indexed workspace, running on one virtual thread.
 *
 * <p>Directories containing watched files are registered with a {@link WatchService}; events are debounced per
 * file, because editors write, truncate and rename in bursts and a single save can produce several events within
 * milliseconds. A callback therefore fires only after a file has been quiet for the debounce interval. Files that
 * disappear (deleted, moved or renamed away) are reported separately and stop being watched.</p>
 *
 * <p>Callbacks run sequentially on the watcher thread, so re-ingestions never overlap. The service has no index,
 * transport or UI dependency — the caller decides what a change means (re-hash, swap chunks, re-announce).</p>
 */
public final class FileWatcherService implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(FileWatcherService.class.getName());
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);
    public static final Duration DEFAULT_DEBOUNCE = Duration.ofMillis(750);
    /**
     * How long a directory whose watch key became invalid may take to reappear (deleted and recreated by a checkout or
     * a sync tool) before its files are given up and {@link Listener#failed} is reported.
     */
    public static final Duration ORPHAN_GRACE = Duration.ofSeconds(5);

    /** Receives debounced file-system changes. */
    public interface Listener {

        /** The file was created, modified or truncated and is present right now. */
        void modified(Path file);

        /** The file disappeared (deleted, moved or renamed away). */
        void deleted(Path file);

        /** Watching {@code path} failed; the caller may register it again later. */
        default void failed(Path path, Exception error) {
        }
    }

    private final Duration debounce;
    private final Listener listener;
    private final Map<Path, WatchKey> directories = new HashMap<>();
    private final Map<Path, Set<Path>> watchedByDirectory = new LinkedHashMap<>();
    private final Map<Path, Long> pending = new LinkedHashMap<>();
    /** Directories whose key became invalid (nanoTime of that moment); their files stay registered meanwhile. */
    private final Map<Path, Long> orphaned = new LinkedHashMap<>();
    private WatchService watchService;
    private Thread worker;
    private volatile boolean running;

    public FileWatcherService(Listener listener) {
        this(DEFAULT_DEBOUNCE, listener);
    }

    public FileWatcherService(Duration debounce, Listener listener) {
        this.debounce = Objects.requireNonNull(debounce, "debounce must not be null");
        this.listener = Objects.requireNonNull(listener, "listener must not be null");
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        watchService = FileSystems.getDefault().newWatchService();
        running = true;
        worker = Thread.ofVirtual().name("dwb-file-watcher").start(this::loop);
    }

    public boolean running() {
        return running;
    }

    public Duration debounce() {
        return debounce;
    }

    /** Starts watching one file; its directory is registered on demand. */
    public synchronized boolean watch(Path file) throws IOException {
        if (!running) {
            throw new IllegalStateException("Watcher is not started");
        }
        Path target = file.toAbsolutePath().normalize();
        Path directory = target.getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            return false;
        }
        Set<Path> files = watchedByDirectory.computeIfAbsent(directory, d -> new HashSet<>());
        if (!files.add(target)) {
            return false;
        }
        if (!directories.containsKey(directory)) {
            directories.put(directory, directory.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE));
        }
        return true;
    }

    public synchronized boolean unwatch(Path file) {
        Path target = file.toAbsolutePath().normalize();
        Path directory = target.getParent();
        Set<Path> files = directory == null ? null : watchedByDirectory.get(directory);
        if (files == null || !files.remove(target)) {
            return false;
        }
        pending.remove(target);
        if (files.isEmpty()) {
            watchedByDirectory.remove(directory);
            orphaned.remove(directory);
            WatchKey key = directories.remove(directory);
            if (key != null) {
                key.cancel();
            }
        }
        return true;
    }

    public synchronized Set<Path> watched() {
        Set<Path> all = new TreeSet<>();
        watchedByDirectory.values().forEach(all::addAll);
        return all;
    }

    public synchronized int directoryCount() {
        return directories.size();
    }

    @Override
    public synchronized void close() {
        if (!running) {
            return;
        }
        running = false;
        directories.values().forEach(WatchKey::cancel);
        directories.clear();
        watchedByDirectory.clear();
        pending.clear();
        orphaned.clear();
        try {
            watchService.close();
        } catch (IOException ignored) {
            // shutting down anyway
        }
        if (worker != null) {
            worker.interrupt();
        }
    }

    // ------------------------------------------------------------------ watcher thread

    private void loop() {
        while (running) {
            try {
                WatchKey key = watchService.poll(POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
                if (key != null) {
                    collect(key);
                }
                dispatchDue();
                recoverOrphans();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException e) {
                return;
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "File watcher iteration failed", e);
            }
        }
    }

    private void collect(WatchKey key) {
        Path directory;
        synchronized (this) {
            // The key knows its directory; it only counts while it is still the registered key for it.
            directory = key.watchable() instanceof Path p && key.equals(directories.get(p)) ? p : null;
        }
        List<WatchEvent<?>> events = key.pollEvents();
        if (directory != null) {
            long now = System.nanoTime();
            synchronized (this) {
                Set<Path> files = watchedByDirectory.getOrDefault(directory, Set.of());
                for (WatchEvent<?> event : events) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        // Events were dropped: re-check every watched file of this directory.
                        files.forEach(file -> pending.put(file, now));
                        continue;
                    }
                    Path changed = directory.resolve(event.context().toString()).normalize();
                    if (files.contains(changed)) {
                        pending.put(changed, now);
                    }
                }
            }
        }
        if (!key.reset() && directory != null) {
            synchronized (this) {
                if (key.equals(directories.get(directory))) {
                    directories.remove(directory);
                    orphaned.put(directory, System.nanoTime());
                }
            }
            recoverOrphans();
        }
    }

    /**
     * Automatic recovery of directories whose key became invalid. A directory that is there again (deleted and
     * recreated by a checkout or sync tool, or a transient file-system hiccup) is registered again and every watched
     * file in it is re-checked through the normal debounce, so changes made meanwhile are neither lost nor silently
     * unwatched: a recreated file is reported modified, a missing one deleted. A directory that stays away for
     * {@link #ORPHAN_GRACE} is given up and reported through {@link Listener#failed}, as before.
     */
    private void recoverOrphans() {
        List<Path> givenUp = new ArrayList<>();
        synchronized (this) {
            if (orphaned.isEmpty() || !running) {
                return;
            }
            long now = System.nanoTime();
            java.util.Iterator<Map.Entry<Path, Long>> it = orphaned.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Path, Long> entry = it.next();
                Path directory = entry.getKey();
                Set<Path> files = watchedByDirectory.get(directory);
                if (files == null || files.isEmpty()) {
                    it.remove(); // everything in it was unwatched meanwhile
                    continue;
                }
                boolean registered = directories.containsKey(directory); // watch() registered it again meanwhile
                if (!registered && Files.isDirectory(directory)) {
                    try {
                        directories.put(directory, directory.register(watchService,
                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_MODIFY,
                                StandardWatchEventKinds.ENTRY_DELETE));
                        registered = true;
                        LOG.log(System.Logger.Level.INFO, "Re-registered watch on {0}", directory);
                    } catch (IOException | RuntimeException e) {
                        LOG.log(System.Logger.Level.DEBUG, "Cannot re-register {0} yet: {1}", directory, e.getMessage());
                    }
                }
                if (registered) {
                    files.forEach(file -> pending.put(file, now));
                    it.remove();
                } else if (now - entry.getValue() >= ORPHAN_GRACE.toNanos()) {
                    it.remove();
                    watchedByDirectory.remove(directory);
                    givenUp.add(directory);
                }
            }
        }
        for (Path directory : givenUp) {
            listener.failed(directory, new IOException("Watch key for " + directory + " is no longer valid"));
        }
    }

    /** Fires callbacks for files that have been quiet for the debounce interval. */
    private void dispatchDue() {
        long now = System.nanoTime();
        long quiet = debounce.toNanos();
        List<Path> due = new ArrayList<>();
        synchronized (this) {
            pending.entrySet().removeIf(entry -> {
                // Difference, not comparison: nanoTime values may be negative and are only meaningful relative.
                if (now - entry.getValue() >= quiet) {
                    due.add(entry.getKey());
                    return true;
                }
                return false;
            });
        }
        for (Path file : due) {
            try {
                if (Files.isRegularFile(file)) {
                    listener.modified(file);
                } else {
                    unwatch(file);
                    listener.deleted(file);
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Watcher callback for " + file + " failed", e);
                listener.failed(file, e);
            }
        }
    }
}
