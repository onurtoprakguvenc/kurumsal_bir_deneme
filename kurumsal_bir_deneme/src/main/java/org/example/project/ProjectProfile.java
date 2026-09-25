package org.example.project;

import org.example.workbench.WorkbenchController;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

/**
 * An isolated project ("workspace") — the equivalent of a project file in a media editor.
 *
 * <p>Everything a project owns lives under its {@link #root()} folder, so a project can be copied, backed up or
 * moved as one directory:</p>
 * <pre>
 * &lt;root&gt;/project.dwbproj          identity + settings (this record, as properties)
 * &lt;root&gt;/index.dwb                binary index snapshot (IndexStorageEngine)
 * &lt;root&gt;/manifest.tsv             human-readable document manifest written on save
 * &lt;root&gt;/keybindings.properties   the project's own shortcuts
 * </pre>
 * <p>The file watcher of a project is scoped to the sources of that project's documents only.</p>
 *
 * @param id            stable identity (survives renames and moves)
 * @param name          display name
 * @param root          absolute project folder
 * @param created       creation time
 * @param watchRoots    folders the user added to this project (informational; used for re-scans)
 * @param formatVersion on-disk layout version
 */
public record ProjectProfile(String id, String name, Path root, Instant created, List<Path> watchRoots,
                             int formatVersion) {

    public static final String PROJECT_FILE = "project.dwbproj";
    public static final int FORMAT_VERSION = 1;

    public ProjectProfile {
        Objects.requireNonNull(id, "id must not be null");
        name = validName(name);
        root = Objects.requireNonNull(root, "root must not be null").toAbsolutePath().normalize();
        created = created == null ? Instant.now() : created;
        watchRoots = List.copyOf(watchRoots);
    }

    /** A new profile with a fresh id; nothing is written until {@link #write()}. */
    public static ProjectProfile create(String name, Path root) {
        return new ProjectProfile(UUID.randomUUID().toString(), name, root, Instant.now(), List.of(), FORMAT_VERSION);
    }

    public Path projectFile() {
        return root.resolve(PROJECT_FILE);
    }

    public Path indexFile() {
        return controllerConfig().indexFile();
    }

    public Path keyBindingsFile() {
        return controllerConfig().keyBindingsFile();
    }

    public Path manifestFile() {
        return root.resolve("manifest.tsv");
    }

    /** Controller configuration whose storage and key-binding paths live inside this project's folder. */
    public WorkbenchController.Config controllerConfig() {
        return WorkbenchController.Config.defaults(root);
    }

    public ProjectProfile withWatchRoot(Path folder) {
        Path normalized = folder.toAbsolutePath().normalize();
        if (watchRoots.contains(normalized)) {
            return this;
        }
        List<Path> next = new ArrayList<>(watchRoots);
        next.add(normalized);
        return new ProjectProfile(id, name, root, created, next, formatVersion);
    }

    /** True when {@code nameOrId} is this project's id, name (case-insensitive) or slug. */
    public boolean matches(String nameOrId) {
        String q = nameOrId.strip();
        return id.equalsIgnoreCase(q) || name.equalsIgnoreCase(q) || slug(name).equals(slug(q));
    }

    // ================================================================== persistence

    /** Reads a project from its {@code .dwbproj} file or from the folder containing it. */
    public static ProjectProfile read(Path fileOrFolder) throws IOException {
        Path file = Files.isDirectory(fileOrFolder) ? fileOrFolder.resolve(PROJECT_FILE) : fileOrFolder;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        String id = p.getProperty("id");
        if (id == null || id.isBlank()) {
            throw new IOException(file + " is not a workbench project (no id)");
        }
        int version;
        try {
            version = Integer.parseInt(p.getProperty("format", "1").strip());
        } catch (NumberFormatException e) {
            throw new IOException(file + ": invalid format version");
        }
        if (version > FORMAT_VERSION) {
            throw new IOException(file + " was written by a newer version (format " + version + ")");
        }
        Instant created;
        try {
            created = Instant.parse(p.getProperty("created", Instant.now().toString()).strip());
        } catch (DateTimeParseException e) {
            created = Instant.now();
        }
        List<Path> roots = new ArrayList<>();
        for (int i = 1; p.containsKey("watch." + i); i++) {
            roots.add(Path.of(p.getProperty("watch." + i)));
        }
        try {
            // The folder the file sits in is authoritative: a moved project keeps working.
            return new ProjectProfile(id.strip(), p.getProperty("name", "Untitled"),
                    file.toAbsolutePath().getParent(), created, roots, version);
        } catch (IllegalArgumentException e) {
            throw new IOException(file + ": " + e.getMessage());
        }
    }

    /** Writes {@code project.dwbproj} atomically, creating the folder if needed. */
    public void write() throws IOException {
        Files.createDirectories(root);
        Properties p = new Properties();
        p.setProperty("id", id);
        p.setProperty("name", name);
        p.setProperty("created", created.toString());
        p.setProperty("format", String.valueOf(formatVersion));
        for (int i = 0; i < watchRoots.size(); i++) {
            p.setProperty("watch." + (i + 1), watchRoots.get(i).toString());
        }
        Path temp = root.resolve(PROJECT_FILE + ".tmp");
        try (Writer w = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            p.store(w, "Document Workbench project");
        }
        try {
            Files.move(temp, projectFile(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, projectFile(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ================================================================== naming

    private static String validName(String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > 80) {
            throw new IllegalArgumentException("project name must have 1-80 characters");
        }
        if (n.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("project name must not contain control characters");
        }
        return n;
    }

    /** Folder-safe ASCII slug: {@code "Kira Sözleşmeleri 2026"} → {@code "kira-sozlesmeleri-2026"}. */
    public static String slug(String name) {
        String folded = Normalizer.normalize(name.strip().replace('ı', 'i').replace('İ', 'I'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        String slug = folded.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (slug.isEmpty()) {
            slug = "project";
        }
        return slug.length() > 48 ? slug.substring(0, 48) : slug;
    }
}
