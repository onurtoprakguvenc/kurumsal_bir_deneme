package org.example.watcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Debounced change detection on real directories (the platform WatchService, no mocks). */
class FileWatcherServiceTest {

    private static final Duration DEBOUNCE = Duration.ofMillis(150);
    private static final long WAIT_MILLIS = 15_000;

    @TempDir
    Path dir;

    private final List<String> events = new CopyOnWriteArrayList<>();
    private FileWatcherService watcher;

    private FileWatcherService start() throws IOException {
        watcher = new FileWatcherService(DEBOUNCE, new FileWatcherService.Listener() {
            @Override
            public void modified(Path file) {
                events.add("M " + file.getFileName());
            }

            @Override
            public void deleted(Path file) {
                events.add("D " + file.getFileName());
            }

            @Override
            public void failed(Path path, Exception error) {
                events.add("F " + path.getFileName());
            }
        });
        watcher.start();
        return watcher;
    }

    @AfterEach
    void stop() {
        if (watcher != null) {
            watcher.close();
        }
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }

    private long count(String event) {
        return events.stream().filter(event::equals).count();
    }

    @Test
    void burstOfWritesIsReportedOnceAfterTheFileIsQuiet() throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "v0");
        start().watch(file);
        for (int i = 1; i <= 5; i++) {
            Files.writeString(file, "v" + i);
            Thread.sleep(20);
        }
        await(() -> count("M a.txt") >= 1, "modification");
        Thread.sleep(DEBOUNCE.toMillis() * 4);
        assertEquals(1, count("M a.txt"), "debounced into one callback: " + events);
    }

    @Test
    void unwatchedSiblingsAreIgnoredAndDeletionIsReported() throws Exception {
        Path watched = Files.writeString(dir.resolve("watched.txt"), "x");
        Path sibling = Files.writeString(dir.resolve("other.txt"), "y");
        start().watch(watched);
        Files.writeString(sibling, "changed");
        Files.delete(watched);
        await(() -> count("D watched.txt") == 1, "deletion");
        Thread.sleep(DEBOUNCE.toMillis() * 3);
        assertTrue(events.stream().noneMatch(e -> e.contains("other.txt")), events.toString());
        assertFalse(watcher.watched().contains(watched.toAbsolutePath().normalize()), "a deleted file stops being watched");
        assertEquals(0, watcher.directoryCount(), "its directory key is released");
    }

    @Test
    void atomicSaveByRenameIsAModificationNotADeletion() throws Exception {
        Path file = Files.writeString(dir.resolve("doc.txt"), "old");
        start().watch(file);
        Path temp = Files.writeString(dir.resolve("doc.txt.tmp"), "new content");
        Files.delete(file);
        Files.move(temp, file);
        await(() -> count("M doc.txt") == 1, "modification after replace");
        assertEquals(0, count("D doc.txt"), events.toString());
    }

    @Test
    void watchBookkeeping() throws Exception {
        Path a = Files.writeString(dir.resolve("a.txt"), "a");
        Path b = Files.writeString(dir.resolve("b.txt"), "b");
        FileWatcherService w = new FileWatcherService(DEBOUNCE, new FileWatcherService.Listener() {
            @Override
            public void modified(Path file) {
            }

            @Override
            public void deleted(Path file) {
            }
        });
        assertThrows(IllegalStateException.class, () -> w.watch(a), "not started");
        watcher = w;
        w.start();
        assertTrue(w.watch(a));
        assertFalse(w.watch(a), "already watched");
        assertTrue(w.watch(b));
        assertFalse(w.watch(dir.resolve("missing-dir").resolve("x.txt")), "no such directory");
        assertEquals(1, w.directoryCount(), "one key per directory");
        assertTrue(w.unwatch(a));
        assertFalse(w.unwatch(a));
        assertEquals(1, w.directoryCount());
        assertTrue(w.unwatch(b));
        assertEquals(0, w.directoryCount());
        w.close();
        assertFalse(w.running());
    }

    /**
     * A watched folder deleted and recreated (a checkout, a sync tool) invalidates its key. The watcher registers the
     * folder again and re-checks its files: a recreated file is a modification, a missing one a deletion.
     */
    @Test
    void recreatedDirectoryIsWatchedAgain() throws Exception {
        Path folder = Files.createDirectory(dir.resolve("proje"));
        Path kept = Files.writeString(folder.resolve("kalan.txt"), "1");
        Path gone = Files.writeString(folder.resolve("giden.txt"), "2");
        start();
        watcher.watch(kept);
        watcher.watch(gone);

        Files.delete(kept);
        Files.delete(gone);
        try {
            Files.delete(folder);
        } catch (IOException e) {
            org.junit.jupiter.api.Assumptions.abort("this platform keeps a watched directory from being deleted");
        }
        // Windows keeps a deleted directory "delete pending" while a handle (the watch) is open on it.
        long deadline = System.currentTimeMillis() + 5_000;
        while (true) {
            try {
                Files.createDirectory(folder);
                break;
            } catch (IOException e) {
                if (System.currentTimeMillis() > deadline) {
                    throw e;
                }
                Thread.sleep(20);
            }
        }
        Files.writeString(kept, "recreated");

        // The folder is registered again within the grace period: the recreated file is a modification, the missing
        // one a deletion, and later changes are reported again.
        await(() -> count("M kalan.txt") >= 1, "reconciliation of the recreated file");
        await(() -> count("D giden.txt") == 1, "missing file reported deleted");
        assertEquals(0, count("F proje"), events.toString());
        events.clear();
        Thread.sleep(DEBOUNCE.toMillis() * 2);
        Files.writeString(kept, "edited again");
        await(() -> count("M kalan.txt") >= 1, "a change after recovery");
        assertTrue(watcher.watched().contains(kept.toAbsolutePath().normalize()));
    }

    @Test
    void aDirectoryThatStaysAwayIsReportedAfterTheGracePeriod() throws Exception {
        Path folder = Files.createDirectory(dir.resolve("gecici"));
        Path file = Files.writeString(folder.resolve("dosya.txt"), "1");
        start().watch(file);
        Files.delete(file);
        try {
            Files.delete(folder);
        } catch (IOException e) {
            org.junit.jupiter.api.Assumptions.abort("this platform keeps a watched directory from being deleted");
        }
        long started = System.currentTimeMillis();
        await(() -> count("D dosya.txt") == 1 || count("F gecici") == 1, "deletion or failure");
        // The file's own delete event normally arrives first; the folder is then no longer needed at all.
        Thread.sleep(FileWatcherService.ORPHAN_GRACE.toMillis() + 1_000 - (System.currentTimeMillis() - started));
        assertTrue(watcher.watched().isEmpty(), events.toString());
        assertEquals(0, watcher.directoryCount());
    }
}
