package org.example.p2p;

import org.example.util.Hashing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Per-document access lists ({@code acl.tsv}, the "authorized keys" of a shared document), used in zero-trust mode.
 *
 * <p>Entries, per SHA-256:</p>
 * <ul>
 *   <li>{@code <fingerprint>} – that device may see and pull the document;</li>
 *   <li>{@code dept:<NAME>} – every trusted device of that department may;</li>
 *   <li>{@code once:<fingerprint>} – a one-shot grant (always used for guests): removed by the first complete,
 *       verified transfer to that device.</li>
 * </ul>
 * <p>A document without entries is visible to every {@link DeviceRole#FULL_PEER} (and to no guest); once it has at
 * least one entry, only the listed devices and departments see it. File format: {@code sha256 TAB entry,entry,…}.</p>
 */
public final class AccessList {

    public static final String FILE = "acl.tsv";
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");
    private static final String DEPT = "dept:";
    private static final String ONCE = "once:";

    private final Path file;
    private final ConcurrentHashMap<String, Set<String>> entries = new ConcurrentHashMap<>();

    AccessList(Path file) throws IOException {
        this.file = file;
        if (Files.isRegularFile(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", 2);
                if (f.length != 2 || !Hashing.isSha256Hex(f[0])) {
                    continue;
                }
                Set<String> set = ConcurrentHashMap.newKeySet();
                for (String e : f[1].split(",")) {
                    try {
                        set.add(entry(e));
                    } catch (IllegalArgumentException ignored) {
                        // unknown entry: dropped
                    }
                }
                if (!set.isEmpty()) {
                    entries.put(f[0], set);
                }
            }
        }
    }

    /**
     * Normalizes one entry: a fingerprint, {@code dept:NAME} / {@code department=NAME}, or {@code once:fingerprint}.
     */
    public static String entry(String raw) {
        String e = raw.strip();
        String lower = e.toLowerCase(Locale.ROOT);
        if (FINGERPRINT.matcher(lower).matches()) {
            return lower;
        }
        if (lower.startsWith(ONCE) && FINGERPRINT.matcher(lower.substring(ONCE.length())).matches()) {
            return lower;
        }
        if (lower.startsWith(DEPT) || lower.startsWith("department=")) {
            return DEPT + TrustStore.department(e.substring(e.indexOf(lower.startsWith(DEPT) ? ':' : '=') + 1));
        }
        throw new IllegalArgumentException("not a device fingerprint, dept:NAME or once:fingerprint: " + raw);
    }

    /** Whether the document has an access list at all (then only listed devices see it). */
    public boolean restricted(String sha256) {
        Set<String> e = entries.get(sha256);
        return e != null && !e.isEmpty();
    }

    /** Whether the list names this device directly, through a one-shot grant or through its department. */
    public boolean permits(String sha256, TrustStore.Device device) {
        Set<String> e = entries.get(sha256);
        if (e == null) {
            return false;
        }
        return e.contains(device.fingerprint()) || e.contains(ONCE + device.fingerprint())
                || (!device.department().isEmpty() && e.contains(DEPT + device.department()));
    }

    /** Whether this device's access to the document is a one-shot grant (and nothing broader). */
    public boolean oneShot(String sha256, TrustStore.Device device) {
        Set<String> e = entries.get(sha256);
        return e != null && e.contains(ONCE + device.fingerprint()) && !e.contains(device.fingerprint())
                && (device.department().isEmpty() || !e.contains(DEPT + device.department()));
    }

    /** Documents this device is listed for (a guest's whole catalog). */
    public Set<String> grantedTo(TrustStore.Device device) {
        Set<String> out = new TreeSet<>();
        entries.forEach((sha, e) -> {
            if (permits(sha, device)) {
                out.add(sha);
            }
        });
        return out;
    }

    public Map<String, List<String>> snapshot() {
        Map<String, List<String>> out = new TreeMap<>();
        entries.forEach((sha, e) -> out.put(sha, List.copyOf(new TreeSet<>(e))));
        return out;
    }

    public List<String> entries(String sha256) {
        Set<String> e = entries.get(sha256);
        return e == null ? List.of() : List.copyOf(new TreeSet<>(e));
    }

    /** Adds an entry; returns false when it was there already. */
    public synchronized boolean grant(String sha256, String entry) throws IOException {
        requireHash(sha256);
        boolean added = entries.computeIfAbsent(sha256, k -> ConcurrentHashMap.newKeySet()).add(entry(entry));
        if (added) {
            save();
        }
        return added;
    }

    /** Removes an entry (a fingerprint also removes its one-shot grant); returns false when nothing changed. */
    public synchronized boolean revoke(String sha256, String entry) throws IOException {
        requireHash(sha256);
        String e = entry(entry);
        Set<String> set = entries.get(sha256);
        if (set == null) {
            return false;
        }
        boolean removed = set.remove(e) | (FINGERPRINT.matcher(e).matches() && set.remove(ONCE + e));
        if (set.isEmpty()) {
            entries.remove(sha256, set);
        }
        if (removed) {
            save();
        }
        return removed;
    }

    /** Uses up a one-shot grant after a verified transfer; returns true when one was consumed. */
    public synchronized boolean consumeOnce(String sha256, String fingerprint) throws IOException {
        Set<String> set = entries.get(sha256);
        if (set == null || !set.remove(ONCE + fingerprint)) {
            return false;
        }
        if (set.isEmpty()) {
            entries.remove(sha256, set);
        }
        save();
        return true;
    }

    /** Drops every entry of a device that is no longer trusted. */
    public synchronized void forget(String fingerprint) throws IOException {
        boolean changed = false;
        for (Map.Entry<String, Set<String>> e : entries.entrySet()) {
            changed |= e.getValue().remove(fingerprint) | e.getValue().remove(ONCE + fingerprint);
            if (e.getValue().isEmpty()) {
                entries.remove(e.getKey(), e.getValue());
            }
        }
        if (changed) {
            save();
        }
    }

    private void save() throws IOException {
        List<String> lines = new ArrayList<>();
        new TreeMap<>(entries).forEach((sha, e) -> {
            if (!e.isEmpty()) {
                lines.add(sha + '\t' + String.join(",", new TreeSet<>(e)));
            }
        });
        Path temp = file.resolveSibling(FILE + ".tmp");
        Files.write(temp, lines, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void requireHash(String sha256) {
        if (!Hashing.isSha256Hex(sha256)) {
            throw new IllegalArgumentException("Not a SHA-256 hex digest: " + sha256);
        }
    }
}
