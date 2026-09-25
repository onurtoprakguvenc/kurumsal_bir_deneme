package org.example.workbench;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The user's own notes about a project's documents: pinned documents, tags, saved searches and the documents opened
 * or previewed recently. Everything lives in one small properties file next to the project's index
 * ({@code notes.properties}), keyed by content hash, and is rewritten atomically on every change.
 *
 * <p>Nothing here influences ranking or ingestion; it only narrows searches ({@code --tag}, {@code --pinned}) and
 * helps people find their way back to documents.</p>
 */
public final class ProjectNotes {

    /** A document path the user opened, revealed or previewed. */
    public record Recent(Path path, String action, Instant at) {
    }

    public static final int MAX_RECENT = 30;
    private static final int MAX_LABEL = 40;
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}_.-]{1,40}");

    private final Path file;
    private final Set<String> pinned = new LinkedHashSet<>();
    private final Map<String, TreeSet<String>> tags = new HashMap<>();
    private final TreeMap<String, String> saved = new TreeMap<>();
    private final Deque<Recent> recent = new ArrayDeque<>();

    public ProjectNotes(Path file) {
        this.file = Objects.requireNonNull(file, "file must not be null").toAbsolutePath().normalize();
    }

    public Path file() {
        return file;
    }

    // ================================================================== persistence

    /** Reads the notes file; a missing file means "no notes yet", unreadable lines are skipped. */
    public synchronized void load() throws IOException {
        pinned.clear();
        tags.clear();
        saved.clear();
        recent.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        List<Recent> recents = new ArrayList<>();
        for (String key : p.stringPropertyNames()) {
            String value = p.getProperty(key);
            try {
                if (key.startsWith("pin.")) {
                    pinned.add(key.substring(4));
                } else if (key.startsWith("tags.")) {
                    TreeSet<String> labels = new TreeSet<>();
                    for (String label : value.split(",")) {
                        if (!label.isBlank()) {
                            labels.add(label.strip());
                        }
                    }
                    if (!labels.isEmpty()) {
                        tags.put(key.substring(5), labels);
                    }
                } else if (key.startsWith("saved.")) {
                    saved.put(key.substring(6), value);
                } else if (key.startsWith("recent.")) {
                    String[] parts = value.split("\t", 3);
                    recents.add(new Recent(Path.of(parts[2]), parts[1], Instant.ofEpochMilli(Long.parseLong(parts[0]))));
                }
            } catch (RuntimeException ignored) {
                // a hand-edited or damaged line; the rest of the notes still load
            }
        }
        recents.sort((a, b) -> b.at().compareTo(a.at()));
        recents.stream().limit(MAX_RECENT).forEach(recent::addLast);
    }

    private void store() throws IOException {
        Properties p = new Properties();
        pinned.forEach(sha -> p.setProperty("pin." + sha, "1"));
        tags.forEach((sha, labels) -> p.setProperty("tags." + sha, String.join(",", labels)));
        saved.forEach((name, line) -> p.setProperty("saved." + name, line));
        int i = 0;
        for (Recent r : recent) {
            p.setProperty("recent." + (i++), r.at().toEpochMilli() + "\t" + r.action() + "\t" + r.path());
        }
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            p.store(w, "Document Workbench - pins, tags, saved searches, recent documents");
        }
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ================================================================== pins

    public synchronized boolean pin(String sha256) throws IOException {
        boolean added = pinned.add(sha256);
        if (added) {
            store();
        }
        return added;
    }

    public synchronized boolean unpin(String sha256) throws IOException {
        boolean removed = pinned.remove(sha256);
        if (removed) {
            store();
        }
        return removed;
    }

    public synchronized boolean isPinned(String sha256) {
        return pinned.contains(sha256);
    }

    public synchronized List<String> pinned() {
        return List.copyOf(pinned);
    }

    // ================================================================== tags

    /** Tag labels are lower-cased single words (letters, digits, {@code _ . -}), at most 40 characters. */
    public static String label(String raw) {
        String l = raw == null ? "" : raw.strip().toLowerCase(Locale.forLanguageTag("tr"));
        if (l.isEmpty() || l.length() > MAX_LABEL || !NAME.matcher(l).matches()) {
            throw new IllegalArgumentException("a tag is one word of letters, digits, '_', '.' or '-' (max "
                    + MAX_LABEL + " characters): '" + raw + "'");
        }
        return l;
    }

    public synchronized boolean tag(String sha256, String rawLabel) throws IOException {
        boolean added = tags.computeIfAbsent(sha256, s -> new TreeSet<>()).add(label(rawLabel));
        if (added) {
            store();
        }
        return added;
    }

    public synchronized boolean untag(String sha256, String rawLabel) throws IOException {
        TreeSet<String> labels = tags.get(sha256);
        boolean removed = labels != null && labels.remove(label(rawLabel));
        if (labels != null && labels.isEmpty()) {
            tags.remove(sha256);
        }
        if (removed) {
            store();
        }
        return removed;
    }

    public synchronized Set<String> tags(String sha256) {
        TreeSet<String> labels = tags.get(sha256);
        return labels == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(labels));
    }

    /** Every label with the number of documents carrying it. */
    public synchronized Map<String, Integer> labels() {
        TreeMap<String, Integer> out = new TreeMap<>();
        tags.values().forEach(set -> set.forEach(l -> out.merge(l, 1, Integer::sum)));
        return out;
    }

    public synchronized Set<String> tagged(String rawLabel) {
        String l = label(rawLabel);
        Set<String> out = new LinkedHashSet<>();
        tags.forEach((sha, labels) -> {
            if (labels.contains(l)) {
                out.add(sha);
            }
        });
        return out;
    }

    // ================================================================== saved searches

    public static String searchName(String raw) {
        String n = raw == null ? "" : raw.strip();
        if (!NAME.matcher(n).matches()) {
            throw new IllegalArgumentException("a saved search name is one word of letters, digits, '_', '.' or '-'"
                    + " (max 40 characters): '" + raw + "'");
        }
        return n;
    }

    public synchronized void saveSearch(String rawName, String searchLine) throws IOException {
        saved.put(searchName(rawName), Objects.requireNonNull(searchLine).strip());
        store();
    }

    public synchronized boolean removeSearch(String rawName) throws IOException {
        boolean removed = saved.remove(searchName(rawName)) != null;
        if (removed) {
            store();
        }
        return removed;
    }

    public synchronized Optional<String> savedSearch(String rawName) {
        return Optional.ofNullable(saved.get(searchName(rawName)));
    }

    public synchronized Map<String, String> savedSearches() {
        return Collections.unmodifiableMap(new TreeMap<>(saved));
    }

    // ================================================================== recent documents

    /** Remembers a document access; failures to persist are ignored (recent documents are a convenience). */
    public synchronized void noteRecent(Path path, String action, Instant at) {
        Path p = path.toAbsolutePath().normalize();
        recent.removeIf(r -> r.path().equals(p));
        recent.addFirst(new Recent(p, action, at));
        while (recent.size() > MAX_RECENT) {
            recent.removeLast();
        }
        try {
            store();
        } catch (IOException ignored) {
            // kept in memory for this session
        }
    }

    public synchronized List<Recent> recent() {
        return List.copyOf(recent);
    }

    // ================================================================== lifecycle of documents

    /** A document was re-indexed under new content: its pin and tags follow it. */
    public synchronized void migrate(String fromSha, String toSha) {
        boolean changed = false;
        if (pinned.remove(fromSha)) {
            pinned.add(toSha);
            changed = true;
        }
        TreeSet<String> labels = tags.remove(fromSha);
        if (labels != null) {
            tags.computeIfAbsent(toSha, s -> new TreeSet<>()).addAll(labels);
            changed = true;
        }
        if (changed) {
            try {
                store();
            } catch (IOException ignored) {
                // re-written with the next change
            }
        }
    }

    /** A document left the index at the user's request: its pin and tags go with it. */
    public synchronized void forget(String sha256) {
        if (pinned.remove(sha256) | tags.remove(sha256) != null) {
            try {
                store();
            } catch (IOException ignored) {
                // re-written with the next change
            }
        }
    }
}
