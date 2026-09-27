package org.example.platform;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Hands files to the operating system: "open with the default application" and "show in the file manager".
 *
 * <p>Every action runs on a virtual thread and returns a {@link CompletableFuture}, so neither the FX thread nor a
 * REPL thread ever waits on process creation (which can take hundreds of milliseconds on a cold Windows box with
 * antivirus hooks). Native commands are launched through {@link ProcessBuilder} with discarded I/O, so no pipe
 * buffers or reader threads are kept alive. {@link Desktop} is only a fallback: initializing AWT costs tens of
 * megabytes and a toolkit thread, which is exactly what this workbench avoids on 4 GB machines.</p>
 *
 * <p>Commands per platform:</p>
 * <ul>
 *   <li>Windows — open: {@code explorer.exe <path>}; reveal: {@code explorer.exe /select, <path>} (the path is a
 *       separate argument so paths with spaces survive Java's argument quoting).</li>
 *   <li>macOS — {@code open <path>} / {@code open -R <path>}.</li>
 *   <li>Linux/BSD — {@code xdg-open <path>}; reveal via the freedesktop {@code FileManager1.ShowItems} D-Bus call,
 *       falling back to opening the parent directory.</li>
 * </ul>
 *
 * <p>Safety: only existing absolute paths are accepted, and "open" refuses executable and script types — the
 * workbench opens documents, and a REPL typo must not run a program.</p>
 */
public final class OsShellBridge implements AutoCloseable {

    /** Host platform families with distinct shell conventions. */
    public enum Platform {
        WINDOWS, MAC, LINUX, OTHER;

        public static Platform current() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                return WINDOWS;
            }
            if (os.contains("mac") || os.contains("darwin")) {
                return MAC;
            }
            if (os.contains("nux") || os.contains("nix") || os.contains("bsd")) {
                return LINUX;
            }
            return OTHER;
        }
    }

    public enum Action {
        OPEN, REVEAL
    }

    /** Outcome of a shell action. */
    public sealed interface ShellResult permits ShellResult.Launched, ShellResult.Rejected, ShellResult.Failed {

        Action action();

        Path path();

        /** A native command or the AWT fallback accepted the request. */
        record Launched(Action action, Path path, String method) implements ShellResult {
        }

        /** The request was refused before anything was launched (missing file, executable type…). */
        record Rejected(Action action, Path path, String reason) implements ShellResult {
        }

        /** Launching was attempted and failed. */
        record Failed(Action action, Path path, String reason) implements ShellResult {
        }

        default boolean ok() {
            return this instanceof Launched;
        }

        default String describe() {
            return switch (this) {
                case Launched l -> l.action() + " " + l.path() + " via " + l.method();
                case Rejected r -> r.action() + " refused: " + r.reason();
                case Failed f -> f.action() + " failed: " + f.reason();
            };
        }
    }

    /** Process creation seam; tests substitute a recorder. */
    @FunctionalInterface
    public interface ProcessLauncher {
        Process start(List<String> command) throws IOException;

        /** Output is discarded and stdin closed immediately, so no pipe or reader thread outlives the call. */
        ProcessLauncher SYSTEM = command -> {
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            process.getOutputStream().close();
            return process;
        };
    }

    private static final Set<String> EXECUTABLE_EXTENSIONS = Set.of(
            "exe", "com", "bat", "cmd", "ps1", "psm1", "vbs", "vbe", "js", "jse", "wsf", "wsh", "msi", "msp",
            "scr", "pif", "lnk", "url", "hta", "cpl", "jar", "reg", "sh", "app", "command", "desktop", "appimage",
            // further Windows launch/handler types, script hosts and installers
            "msc", "msix", "msixbundle", "appx", "appxbundle", "appref-ms", "application", "settingcontent-ms",
            "library-ms", "search-ms", "searchconnector-ms", "website", "chm", "inf", "scf", "xll", "gadget",
            "diagcab", "mst", "ps1xml", "ps2", "ps2xml", "psc1", "psc2", "psd1", "wsc", "shb", "shs", "vb",
            "vbscript", "ws", "sct", "mde", "ade", "adp", "crt", "ins", "isp", "jnlp", "py", "pyw", "pyc", "pyz",
            "pl", "rb", "php", "csh", "ksh", "bash", "zsh", "run", "bin", "elf", "deb", "rpm", "pkg", "dmg");
    /** How long to watch a launcher for an immediate failure exit on platforms whose exit codes are meaningful. */
    private static final long EXIT_PROBE_MILLIS = 1_500;

    private final Platform platform;
    private final ProcessLauncher launcher;
    private final boolean desktopFallback;
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("dwb-shell-", 0).factory());

    public OsShellBridge() {
        this(Platform.current(), ProcessLauncher.SYSTEM, true);
    }

    /**
     * @param desktopFallback whether to try {@link Desktop} when the native command cannot be started
     */
    public OsShellBridge(Platform platform, ProcessLauncher launcher, boolean desktopFallback) {
        this.platform = Objects.requireNonNull(platform, "platform must not be null");
        this.launcher = Objects.requireNonNull(launcher, "launcher must not be null");
        this.desktopFallback = desktopFallback;
    }

    public Platform platform() {
        return platform;
    }

    /** Opens {@code file} with its default application. */
    public CompletableFuture<ShellResult> open(Path file) {
        return submit(Action.OPEN, file);
    }

    /** Shows {@code file} highlighted in the platform file manager (a directory is simply opened). */
    public CompletableFuture<ShellResult> reveal(Path file) {
        return submit(Action.REVEAL, file);
    }

    private CompletableFuture<ShellResult> submit(Action action, Path file) {
        if (file == null) {
            return CompletableFuture.completedFuture(new ShellResult.Rejected(action, null, "no path"));
        }
        try {
            return CompletableFuture.supplyAsync(() -> perform(action, file), executor);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return CompletableFuture.completedFuture(new ShellResult.Rejected(action, file, "shell bridge closed"));
        }
    }

    /** Synchronous core; public for headless runners that want deterministic sequencing. */
    public ShellResult perform(Action action, Path file) {
        Path path;
        try {
            path = file.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return new ShellResult.Rejected(action, file, "invalid path: " + e.getMessage());
        }
        if (!Files.exists(path)) {
            return new ShellResult.Rejected(action, path, "file does not exist");
        }
        if (action == Action.OPEN && Files.isRegularFile(path) && isExecutable(path)) {
            return new ShellResult.Rejected(action, path, "executable and script files are never opened");
        }
        List<List<String>> commands = commandsFor(action, path);
        String lastError = "no native command for " + platform;
        for (List<String> command : commands) {
            try {
                Process process = launcher.start(command);
                if (failedFast(process)) {
                    lastError = command.getFirst() + " exited with " + process.exitValue();
                    continue;
                }
                return new ShellResult.Launched(action, path, String.join(" ", command.subList(0, command.size() - 1)));
            } catch (IOException | RuntimeException e) {
                lastError = command.getFirst() + ": " + e.getMessage();
            }
        }
        if (desktopFallback) {
            ShellResult fallback = viaDesktop(action, path);
            if (fallback != null) {
                return fallback;
            }
        }
        return new ShellResult.Failed(action, path, lastError);
    }

    /** Candidate command lines in preference order; the path is always the last element. */
    public List<List<String>> commandsFor(Action action, Path path) {
        String p = path.toString();
        boolean directory = Files.isDirectory(path);
        return switch (platform) {
            case WINDOWS -> action == Action.REVEAL && !directory
                    ? List.of(List.of("explorer.exe", "/select,", p))
                    : List.of(List.of("explorer.exe", p));
            case MAC -> action == Action.REVEAL && !directory
                    ? List.of(List.of("open", "-R", p))
                    : List.of(List.of("open", p));
            case LINUX -> action == Action.REVEAL && !directory
                    ? List.of(List.of("dbus-send", "--session", "--dest=org.freedesktop.FileManager1",
                                    "--type=method_call", "/org/freedesktop/FileManager1",
                                    "org.freedesktop.FileManager1.ShowItems",
                                    "array:string:" + path.toUri(), "string:"),
                            List.of("xdg-open", parentOf(path).toString()))
                    : List.of(List.of("xdg-open", p));
            case OTHER -> List.of();
        };
    }

    /**
     * explorer.exe returns 1 even on success, so Windows exit codes are ignored; elsewhere a launcher that dies
     * within {@value #EXIT_PROBE_MILLIS} ms with a non-zero code is treated as a failure so the next candidate runs.
     * This only blocks the virtual thread performing the action.
     */
    private boolean failedFast(Process process) {
        if (platform == Platform.WINDOWS) {
            return false;
        }
        try {
            return process.waitFor(EXIT_PROBE_MILLIS, TimeUnit.MILLISECONDS) && process.exitValue() != 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private ShellResult viaDesktop(Action action, Path path) {
        if (Boolean.getBoolean("java.awt.headless") || !Desktop.isDesktopSupported()) {
            return null;
        }
        try {
            Desktop desktop = Desktop.getDesktop();
            if (action == Action.REVEAL && desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)
                    && Files.isRegularFile(path)) {
                desktop.browseFileDirectory(path.toFile());
                return new ShellResult.Launched(action, path, "java.awt.Desktop#browseFileDirectory");
            }
            Path target = action == Action.REVEAL && Files.isRegularFile(path) ? parentOf(path) : path;
            if (desktop.isSupported(Desktop.Action.OPEN)) {
                desktop.open(target.toFile());
                return new ShellResult.Launched(action, path, "java.awt.Desktop#open");
            }
        } catch (IOException | RuntimeException e) {
            return new ShellResult.Failed(action, path, "java.awt.Desktop: " + e.getMessage());
        }
        return null;
    }

    public static boolean isExecutable(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && EXECUTABLE_EXTENSIONS.contains(name.substring(dot + 1));
    }

    private static Path parentOf(Path path) {
        Path parent = path.getParent();
        return parent == null ? path : parent;
    }

    /** Stops accepting actions; already started processes are independent of the JVM and keep running. */
    @Override
    public void close() {
        executor.shutdown();
    }
}
