package org.example.p2p;

import org.example.model.BinaryAsset;
import org.example.model.ContentKind;
import org.example.model.DocumentRecord;
import org.example.util.Hashing;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local content-addressed store.
 *
 * <ul>
 *   <li><b>Registered files</b>: local originals that were indexed in place. They are served to peers as long as
 *       size and modification time still match the values seen at registration (no copy is made).</li>
 *   <li><b>Objects</b>: files received from peers, stored as {@code objects/ab/<sha256>} with the original name in a
 *       {@code .name} sidecar. Names coming from the network never influence file system paths.</li>
 *   <li><b>Partials</b>: {@code partial/<sha256>.dwb_part}, kept across interruptions so transfers resume; renamed
 *       into {@code objects/} only after the complete file's SHA-256 matched.</li>
 * </ul>
 *
 * <p>Registration is unified: searchable text documents and raw binary media are both addressed by SHA-256 and
 * served identically. The {@link ContentKind} recorded with a registration only tells callers which track the
 * content took (indexed text or blob-only); the bytes on the wire are the same.</p>
 */
public final class ContentStore {

    public record StoredFile(String sha256, Path path, String name, long size) {
    }

    private record Registration(Path path, String name, long size, FileTime modified, ContentKind kind,
                                Instant registeredAt) {
    }

    /** Suffix of in-flight transfers; the legacy {@code .part} suffix is still cleaned up. */
    public static final String PARTIAL_SUFFIX = ".dwb_part";
    private static final String PARTIAL_GLOB = "*.{dwb_part,part}";

    private static final int MAX_NAME_CHARS = 180;
    /** Most file systems (ext4, XFS, APFS, NTFS in UTF-16 units) allow 255 per name; this counts UTF-8 bytes. */
    public static final int MAX_NAME_BYTES = 255;
    /** Extensions up to this length are kept when a name has to be shortened. */
    private static final int MAX_KEPT_EXTENSION_CHARS = 16;

    private final Path root;
    private final Path objects;
    private final Path partial;
    private final ConcurrentHashMap<String, Registration> registered = new ConcurrentHashMap<>();

    public ContentStore(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.objects = this.root.resolve("objects");
        this.partial = this.root.resolve("partial");
        this.unsharedFile = this.root.resolve("unshared.txt");
        this.assetsFile = this.root.resolve(ASSETS_FILE);
        Files.createDirectories(objects);
        Files.createDirectories(partial);
        if (Files.isRegularFile(unsharedFile)) {
            for (String line : Files.readAllLines(unsharedFile, StandardCharsets.UTF_8)) {
                if (Hashing.isSha256Hex(line.strip())) {
                    unshared.add(line.strip());
                }
            }
        }
        loadAssets();
        this.acl = new AccessList(this.root.resolve(AccessList.FILE));
    }

    // ------------------------------------------------------------------ access lists (zero-trust mode)

    private final AccessList acl;

    /** Per-document access lists ({@code acl.tsv}); consulted only in zero-trust mode. */
    public AccessList acl() {
        return acl;
    }

    // ------------------------------------------------------------------ binary registrations (persistent)

    /**
     * Local binary files registered for sharing: {@code sha256 TAB size TAB modifiedMillis TAB path TAB name TAB registeredMillis}. Text
     * documents are not listed here (their index snapshot re-registers them); binary assets have no other record, so
     * without this file they would silently stop being shared after a restart.
     */
    static final String ASSETS_FILE = "assets.tsv";
    private final Path assetsFile;

    /** Restores binary registrations whose file still has the recorded size and modification time. */
    private void loadAssets() {
        if (!Files.isRegularFile(assetsFile)) {
            return;
        }
        boolean dropped = false;
        try {
            for (String line : Files.readAllLines(assetsFile, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", 6);
                if (f.length < 5 || !Hashing.isSha256Hex(f[0])) {
                    dropped |= !line.isBlank();
                    continue;
                }
                try {
                    Path file = Path.of(f[3]);
                    BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                    if (attrs.isRegularFile() && attrs.size() == Long.parseLong(f[1])
                            && attrs.lastModifiedTime().toMillis() == Long.parseLong(f[2])) {
                        Instant at = f.length == 6 ? Instant.ofEpochMilli(Long.parseLong(f[5]))
                                : attrs.lastModifiedTime().toInstant();
                        registered.put(f[0], new Registration(file, sanitizeName(f[4]), attrs.size(),
                                attrs.lastModifiedTime(), ContentKind.BINARY, at));
                    } else {
                        dropped = true; // changed or moved since it was hashed
                    }
                } catch (IOException | RuntimeException e) {
                    dropped = true; // gone or unreadable
                }
            }
        } catch (IOException e) {
            return; // unreadable list: start without restored assets, keep the file for the next attempt
        }
        if (dropped) {
            saveAssetsQuietly();
        }
    }

    private synchronized void saveAssets() throws IOException {
        List<String> lines = new ArrayList<>();
        new TreeSet<>(registered.keySet()).forEach(sha -> {
            Registration r = registered.get(sha);
            String path = r == null ? "" : r.path().toString();
            if (r != null && r.kind() == ContentKind.BINARY && path.indexOf('\t') < 0 && path.indexOf('\n') < 0) {
                lines.add(sha + '\t' + r.size() + '\t' + r.modified().toMillis() + '\t' + path + '\t' + r.name()
                        + '\t' + r.registeredAt().toEpochMilli());
            }
        });
        if (lines.isEmpty() && !Files.exists(assetsFile)) {
            return;
        }
        Path temp = assetsFile.resolveSibling(assetsFile.getFileName() + ".tmp");
        Files.write(temp, lines, StandardCharsets.UTF_8);
        try {
            Files.move(temp, assetsFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, assetsFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void saveAssetsQuietly() {
        try {
            saveAssets();
        } catch (IOException e) {
            // best effort: the in-memory registrations stay valid for this run
        }
    }

    /**
     * Binary files registered from local disk (not objects received from peers), with their registration time.
     * Registrations whose file changed or disappeared are dropped on the way.
     */
    public List<BinaryAsset> registeredAssets() {
        List<BinaryAsset> out = new ArrayList<>();
        for (String sha : new TreeSet<>(registered.keySet())) {
            Registration r = registered.get(sha);
            if (r != null && r.kind() == ContentKind.BINARY && resolve(sha).isPresent()) {
                out.add(new BinaryAsset(sha, r.name(), r.path(), r.size(), r.registeredAt(), DocumentRecord.LOCAL));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ sharing switch

    private final Path unsharedFile;
    /** Content this node keeps to itself: never advertised, never served (kept across restarts). */
    private final Set<String> unshared = ConcurrentHashMap.newKeySet();

    /** Whether peers may see and download this content. Everything is shared unless switched off. */
    public boolean isShared(String sha256) {
        return !unshared.contains(sha256);
    }

    /** Switches sharing of one document on or off; returns false when nothing changed. */
    public synchronized boolean setShared(String sha256, boolean shared) throws IOException {
        requireHash(sha256);
        boolean changed = shared ? unshared.remove(sha256) : unshared.add(sha256);
        if (changed) {
            Path temp = unsharedFile.resolveSibling(unsharedFile.getFileName() + ".tmp");
            Files.write(temp, new TreeSet<>(unshared), StandardCharsets.UTF_8);
            try {
                Files.move(temp, unsharedFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, unsharedFile, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return changed;
    }

    /** What discovery advertises: every hash this node could serve, minus the ones switched off. */
    public Set<String> sharedHashes() {
        Set<String> out = hashes();
        out.removeAll(unshared);
        return out;
    }

    public Path root() {
        return root;
    }

    /** Registers an indexed document (kind from its name, {@link ContentKind#TEXT} when undecided). */
    public void register(String sha256, Path file, String name) throws IOException {
        register(sha256, file, name, ContentKind.fromFileName(name).orElse(ContentKind.TEXT));
    }

    /**
     * Registers a local file under its SHA-256 for serving in place (no copy), whatever its kind. The caller has
     * already streamed the hash; only size and modification time are recorded, so a later change of the file
     * silently invalidates the registration.
     */
    public void register(String sha256, Path file, String name, ContentKind kind) throws IOException {
        requireHash(sha256);
        Objects.requireNonNull(kind, "kind must not be null");
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        Registration previous = registered.put(sha256, new Registration(file.toAbsolutePath().normalize(),
                sanitizeName(name), attrs.size(), attrs.lastModifiedTime(), kind, Instant.now()));
        if (kind == ContentKind.BINARY || (previous != null && previous.kind() == ContentKind.BINARY)) {
            saveAssets();
        }
    }

    /** Kind recorded at registration; empty for unregistered content (e.g. objects received from peers). */
    public Optional<ContentKind> registeredKind(String sha256) {
        Registration r = registered.get(sha256);
        return r == null ? Optional.empty() : Optional.of(r.kind());
    }

    public void unregister(String sha256) {
        Registration removed = registered.remove(sha256);
        if (removed != null && removed.kind() == ContentKind.BINARY) {
            saveAssetsQuietly();
        }
    }

    public boolean has(String sha256) {
        return resolve(sha256).isPresent();
    }

    public Optional<StoredFile> resolve(String sha256) {
        if (!Hashing.isSha256Hex(sha256)) {
            return Optional.empty();
        }
        Registration r = registered.get(sha256);
        if (r != null) {
            try {
                BasicFileAttributes attrs = Files.readAttributes(r.path(), BasicFileAttributes.class);
                if (attrs.size() == r.size() && attrs.lastModifiedTime().equals(r.modified())) {
                    return Optional.of(new StoredFile(sha256, r.path(), r.name(), r.size()));
                }
            } catch (IOException e) {
                // fall through: stale registration
            }
            if (registered.remove(sha256, r) && r.kind() == ContentKind.BINARY) {
                saveAssetsQuietly();
            }
        }
        Path object = objectPath(sha256);
        try {
            long size = Files.size(object);
            return Optional.of(new StoredFile(sha256, object, readName(sha256), size));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public Path partialPath(String sha256) {
        requireHash(sha256);
        return partial.resolve(sha256 + PARTIAL_SUFFIX);
    }

    /** Bytes still free for partial files and objects (both live on the store's file system). */
    public long usableSpace() {
        try {
            return Files.getFileStore(partial).getUsableSpace();
        } catch (IOException e) {
            return Long.MAX_VALUE; // unknown: let the write itself fail if the disk is full
        }
    }

    /** Deletes unfinished transfers not touched for {@code maxAge}; returns how many were removed. */
    public int purgeStalePartials(java.time.Duration maxAge) {
        long cutoff = System.currentTimeMillis() - maxAge.toMillis();
        int removed = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(partial, PARTIAL_GLOB)) {
            for (Path file : files) {
                try {
                    if (Files.getLastModifiedTime(file).toMillis() < cutoff && Files.deleteIfExists(file)) {
                        removed++;
                    }
                } catch (IOException e) {
                    // in use or already gone
                }
            }
        } catch (IOException e) {
            // partial directory unreadable: nothing to clean
        }
        return removed;
    }

    /** Total size of all unfinished transfers. */
    public long partialBytes() {
        long total = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(partial, PARTIAL_GLOB)) {
            for (Path file : files) {
                try {
                    total += Files.size(file);
                } catch (IOException e) {
                    // vanished meanwhile
                }
            }
        } catch (IOException e) {
            return 0;
        }
        return total;
    }

    /** Flushes a verified partial file to disk and atomically moves it into the object store. */
    public StoredFile commit(Path verifiedPartial, String sha256, String name) throws IOException {
        requireHash(sha256);
        Path target = objectPath(sha256);
        Files.createDirectories(target.getParent());
        // The bytes must be on the disk before the rename publishes them as verified content: after a power loss
        // a renamed but unflushed file could otherwise be served and indexed with holes in it.
        try (FileChannel channel = FileChannel.open(verifiedPartial, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        String clean = sanitizeName(name);
        Files.writeString(nameFile(sha256), clean, StandardCharsets.UTF_8);
        try {
            Files.move(verifiedPartial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(verifiedPartial, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return new StoredFile(sha256, target, clean, Files.size(target));
    }

    /** Objects received from peers (used to rebuild the RAM index at startup). */
    public List<StoredFile> objects() throws IOException {
        List<StoredFile> out = new ArrayList<>();
        try (DirectoryStream<Path> buckets = Files.newDirectoryStream(objects, Files::isDirectory)) {
            for (Path bucket : buckets) {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(bucket)) {
                    for (Path file : files) {
                        String name = file.getFileName().toString();
                        if (Hashing.isSha256Hex(name) && Files.isRegularFile(file)) {
                            out.add(new StoredFile(name, file, readName(name), Files.size(file)));
                        }
                    }
                }
            }
        }
        return out;
    }

    /** Every hash this node can serve right now. */
    public Set<String> hashes() {
        Set<String> out = new TreeSet<>(registered.keySet());
        try {
            for (StoredFile file : objects()) {
                out.add(file.sha256());
            }
        } catch (IOException e) {
            // object directory unreadable: advertise registered files only
        }
        return out;
    }

    /** Resolves a hash prefix (min. 4 hex characters) against locally available content. */
    public Optional<String> resolvePrefix(String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        if (p.length() < 4) {
            return Optional.empty();
        }
        List<String> matches = hashes().stream().filter(h -> h.startsWith(p)).toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    private Path objectPath(String sha256) {
        return objects.resolve(sha256.substring(0, 2)).resolve(sha256);
    }

    private Path nameFile(String sha256) {
        return objects.resolve(sha256.substring(0, 2)).resolve(sha256 + ".name");
    }

    private String readName(String sha256) {
        try {
            return sanitizeName(Files.readString(nameFile(sha256), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return sha256.substring(0, 12);
        }
    }

    /** Reduces a peer-supplied name to a harmless display label. */
    public static String sanitizeName(String name) {
        if (name == null) {
            return "document";
        }
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        StringBuilder sb = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (!Character.isISOControl(c) && c != ':' && c != '*' && c != '?' && c != '"' && c != '<' && c != '>'
                    && c != '|') {
                sb.append(c);
            }
        }
        String clean = sb.toString().strip();
        while (clean.startsWith(".")) {
            clean = clean.substring(1);
        }
        clean = fit(clean, MAX_NAME_CHARS, MAX_NAME_BYTES).strip();
        return clean.isEmpty() ? "document" : clean;
    }

    /** {@link #fit} with only the UTF-8 byte limit (used when a suffix is appended to a sanitized name). */
    public static String fitBytes(String name, int maxBytes) {
        return fit(name, Integer.MAX_VALUE, maxBytes);
    }

    /**
     * Shortens {@code name} to at most {@code maxChars} characters and {@code maxBytes} UTF-8 bytes (multi-byte letters
     * such as ğ, ş, ı count 2) by cutting the stem at a code-point boundary and keeping a short extension, so a long
     * {@code ….mp4} still says what it is.
     */
    private static String fit(String name, int maxChars, int maxBytes) {
        if (name.length() <= maxChars && utf8Length(name) <= maxBytes) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String ext = dot > 0 && name.length() - dot <= MAX_KEPT_EXTENSION_CHARS + 1 ? name.substring(dot) : "";
        int charBudget = maxChars - ext.length();
        int byteBudget = maxBytes - utf8Length(ext);
        String stem = ext.isEmpty() ? name : name.substring(0, dot);
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < stem.length(); ) {
            int cp = stem.codePointAt(i);
            int bytes = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (used + bytes > byteBudget || sb.length() + Character.charCount(cp) > charBudget) {
                break;
            }
            sb.appendCodePoint(cp);
            used += bytes;
            i += Character.charCount(cp);
        }
        return sb.toString().stripTrailing() + ext;
    }

    private static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void requireHash(String sha256) {
        if (!Hashing.isSha256Hex(Objects.requireNonNull(sha256, "sha256 must not be null"))) {
            throw new IllegalArgumentException("Not a SHA-256 hex digest: " + sha256);
        }
    }
}
