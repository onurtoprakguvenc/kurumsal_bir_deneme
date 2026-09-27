package org.example.workspace;

import org.example.model.BinaryAsset;
import org.example.model.ContentKind;
import org.example.model.DocumentRecord;
import org.example.model.DocumentType;
import org.example.p2p.ContentStore;
import org.example.platform.OsShellBridge;

import java.awt.Desktop;
import java.io.IOException;
import java.io.Serial;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Catalog of every document indexed (and every binary asset registered) in this workspace plus the bridge that
 * hands a file to the operating system.
 *
 * <p>Opening is delegated, never rendered: {@link Desktop} when a desktop session is available, otherwise the
 * platform launcher ({@code explorer}, {@code open}, {@code xdg-open}). The engine stays headless — it starts the
 * native application and returns immediately. Only files that are in the catalog can be opened, so a path can
 * never be injected through a CLI argument or a peer-supplied name.</p>
 */
public final class WorkspaceManager {

    /**
     * One catalogued file: an indexed document, or a binary asset (video, image, archive, …) registered for sharing.
     *
     * @param type {@code null} for a binary asset, which has no document format
     * @param kind which track the file took; binary assets are opened like documents but are never searchable
     */
    public record Entry(String sha256, String fileName, Path path, DocumentType type, long sizeBytes,
                        int pageCount, Instant indexedAt, ContentKind kind) {

        public Entry {
            kind = kind == null ? ContentKind.TEXT : kind;
        }

        public String shortId() {
            return sha256.substring(0, 12);
        }

        public boolean binary() {
            return kind == ContentKind.BINARY;
        }
    }

    /** How a file was handed to the operating system. */
    public record OpenResult(Entry entry, String method) {
    }

    /** The file cannot be opened (unknown, missing, or no launcher available). */
    public static final class WorkspaceException extends Exception {
        @Serial
        private static final long serialVersionUID = 1L;

        public WorkspaceException(String message) {
            super(message);
        }

        public WorkspaceException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final long LAUNCH_TIMEOUT_SECONDS = 10;

    private final Map<String, Entry> catalog = new LinkedHashMap<>();

    public synchronized Entry register(DocumentRecord document) {
        Objects.requireNonNull(document, "document must not be null");
        Entry entry = new Entry(document.sha256(), document.fileName(), document.source(), document.type(),
                document.sizeBytes(), document.pageCount(), document.ingestedAt(), ContentKind.TEXT);
        catalog.put(entry.sha256(), entry);
        return entry;
    }

    /** Catalogues a binary asset so it can be listed, resolved and opened like a document. */
    public synchronized Entry register(BinaryAsset asset) {
        Objects.requireNonNull(asset, "asset must not be null");
        Entry entry = new Entry(asset.sha256(), asset.fileName(), asset.source(), null, asset.sizeBytes(), -1,
                asset.registeredAt(), ContentKind.BINARY);
        catalog.put(entry.sha256(), entry);
        return entry;
    }

    public synchronized boolean remove(String sha256) {
        return catalog.remove(sha256) != null;
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(catalog.values());
    }

    public synchronized int size() {
        return catalog.size();
    }

    /** Total size of catalogued files. */
    public synchronized long totalBytes() {
        return catalog.values().stream().mapToLong(Entry::sizeBytes).sum();
    }

    /**
     * Resolves a catalog entry (document or binary asset) from a SHA-256 prefix (4+ hex characters), an exact file
     * name, or an unambiguous case-insensitive name fragment.
     */
    public synchronized Optional<Entry> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String needle = token.strip();
        String lower = needle.toLowerCase(Locale.ROOT);
        List<Entry> byHash = new ArrayList<>();
        List<Entry> exactName = new ArrayList<>();
        List<Entry> partialName = new ArrayList<>();
        for (Entry entry : catalog.values()) {
            if (needle.length() >= 4 && entry.sha256().startsWith(lower)) {
                byHash.add(entry);
            }
            String name = entry.fileName().toLowerCase(Locale.ROOT);
            if (name.equals(lower)) {
                exactName.add(entry);
            } else if (name.contains(lower)) {
                partialName.add(entry);
            }
        }
        for (List<Entry> candidates : List.of(byHash, exactName, partialName)) {
            if (candidates.size() == 1) {
                return Optional.of(candidates.getFirst());
            }
            if (candidates.size() > 1) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Resolves {@code token} and opens it in its native application. */
    public OpenResult open(String token) throws WorkspaceException {
        Entry entry = resolve(token).orElseThrow(() ->
                new WorkspaceException("No unique indexed document matches '" + token + "'"));
        return open(entry);
    }

    public OpenResult open(Entry entry) throws WorkspaceException {
        Objects.requireNonNull(entry, "entry must not be null");
        Path path = entry.path();
        if (!Files.isReadable(path) || !Files.isRegularFile(path)) {
            throw new WorkspaceException("File is no longer available: " + path);
        }
        path = withDisplayExtension(entry, path);
        if (OsShellBridge.isExecutable(path)) {
            throw new WorkspaceException("Executable and script files are never opened: " + path.getFileName());
        }
        if (!Boolean.getBoolean("java.awt.headless") && Desktop.isDesktopSupported()) {
            try {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.OPEN)) {
                    desktop.open(path.toFile());
                    return new OpenResult(entry, "java.awt.Desktop");
                }
            } catch (IOException | RuntimeException e) {
                // Fall through to the platform launcher below.
            }
        }
        List<String> command = launcher(path);
        try {
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            // Launchers return immediately; a non-zero exit means no handler was found.
            if (process.waitFor(LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() != 0) {
                throw new WorkspaceException("Launcher '" + command.getFirst() + "' exited with "
                        + process.exitValue());
            }
            return new OpenResult(entry, String.join(" ", command));
        } catch (IOException e) {
            throw new WorkspaceException("No native launcher available (" + command.getFirst() + "): "
                    + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkspaceException("Interrupted while launching " + path.getFileName(), e);
        }
    }

    /**
     * Deletes the physical file of a catalogued document and untracks it. The caller is responsible for the
     * interactive confirmation; this method never asks and never touches a path outside the catalog.
     *
     * @return {@code true} when a file was deleted, {@code false} when it was already gone
     */
    public boolean purge(Entry entry) throws WorkspaceException {
        Objects.requireNonNull(entry, "entry must not be null");
        try {
            boolean deleted = Files.deleteIfExists(entry.path());
            remove(entry.sha256());
            return deleted;
        } catch (IOException e) {
            throw new WorkspaceException("Cannot delete " + entry.path() + ": " + e.getMessage(), e);
        }
    }

    /** Platform launcher command for a path (arguments are passed directly, never through a shell). */
    /**
     * Documents received from peers are stored content-addressed without an extension, which leaves the OS with no
     * application to open them. Such files are opened through a copy named after the document (in the temp folder);
     * everything else is opened in place.
     */
    private static Path withDisplayExtension(Entry entry, Path path) throws WorkspaceException {
        String stored = path.getFileName() == null ? "" : path.getFileName().toString();
        String display = ContentStore.sanitizeName(entry.fileName());
        if (stored.contains(".") || !display.contains(".")) {
            return path;
        }
        try {
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "dwb-open", entry.shortId());
            Files.createDirectories(dir);
            Path copy = dir.resolve(display);
            if (!Files.isRegularFile(copy) || Files.size(copy) != Files.size(path)) {
                Files.deleteIfExists(copy);
                try {
                    // A hard link costs nothing; copying a multi-gigabyte video just to open it would take minutes.
                    Files.createLink(copy, path);
                } catch (IOException | UnsupportedOperationException e) {
                    Files.copy(path, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return copy;
        } catch (IOException | RuntimeException e) {
            throw new WorkspaceException("Cannot prepare " + display + " for opening: " + e.getMessage(), e);
        }
    }

    static List<String> launcher(Path path) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String file = path.toAbsolutePath().toString();
        if (os.contains("win")) {
            return List.of("rundll32", "url.dll,FileProtocolHandler", file);
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return List.of("open", file);
        }
        return List.of("xdg-open", file);
    }
}
