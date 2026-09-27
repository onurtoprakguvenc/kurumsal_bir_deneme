package org.example.workbench;

import org.example.util.AppInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Writes this installation's health summary to a shared folder chosen by IT ({@code report.folder} in the central
 * policy) every {@code report.intervalMinutes}, so IT sees every laptop's state in one place without a server: one
 * small {@code <computer>-<user>.txt} file per machine, replaced atomically each time.
 *
 * <p>The report holds {@link Diagnostics#facts} only — version, memory, document counts, health findings — never a
 * document name, text or search. A missing or unreachable share (laptop off the office network) is simply skipped
 * until the next run; nothing is retried in a loop and the user is not bothered.</p>
 */
public final class HealthReporter implements AutoCloseable {

    private final ExtendedWorkbenchController ext;
    private final Path folder;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("dwb-health-report").daemon(true).factory());
    private volatile String lastProblem;
    private volatile Path lastWritten;

    public HealthReporter(ExtendedWorkbenchController ext, Path folder, int intervalMinutes) {
        this.ext = Objects.requireNonNull(ext, "ext must not be null");
        this.folder = Objects.requireNonNull(folder, "folder must not be null");
        long every = Math.max(5, intervalMinutes);
        // First report shortly after startup (the project is warm by then), then on the interval.
        timer.scheduleWithFixedDelay(this::reportQuietly, 60, every * 60, TimeUnit.SECONDS);
    }

    public Path folder() {
        return folder;
    }

    /** The last write failure (e.g. share unreachable), or {@code null} after a success. */
    public String lastProblem() {
        return lastProblem;
    }

    public Path lastWritten() {
        return lastWritten;
    }

    /** Writes the report now; returns the file written. */
    public Path reportNow() throws IOException {
        Map<String, String> facts = Diagnostics.facts(ext);
        StringBuilder sb = new StringBuilder("# Document Workbench health report (no document contents)\n");
        facts.forEach((k, v) -> sb.append(k).append('=').append(v.replace('\n', ' ')).append('\n'));
        if (!Files.isDirectory(folder)) {
            throw new IOException("report folder " + folder + " is not reachable");
        }
        Path target = folder.resolve(AppInfo.machineId() + ".txt");
        Path temp = folder.resolve("." + AppInfo.machineId() + ".tmp");
        Files.writeString(temp, sb, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        lastWritten = target;
        lastProblem = null;
        return target;
    }

    private void reportQuietly() {
        try {
            reportNow();
        } catch (IOException | RuntimeException e) {
            lastProblem = e.getMessage();
        }
    }

    @Override
    public void close() {
        timer.shutdownNow();
    }
}
