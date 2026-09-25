package org.example.p2p;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The devices this node trusts ({@code trusted-peers.tsv}): only their public keys pass the transfer handshake and only
 * their announcements are heard. A device gets here by pairing ({@link ZeroTrust#openPairing}), never by showing up on
 * the network.
 *
 * <p>One line per device: {@code fingerprint TAB role TAB department TAB name TAB base64(public key) TAB addedMillis}.
 * A line whose fingerprint does not match its key, or whose key is not Ed25519, is ignored.</p>
 */
public final class TrustStore {

    private static final System.Logger LOG = System.getLogger(TrustStore.class.getName());

    public static final String FILE = "trusted-peers.tsv";
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern DEPARTMENT = Pattern.compile("[A-Z0-9_-]{1,32}");
    private static final int MAX_NAME = 64;

    /** One trusted device. {@code department} is empty when none was assigned. */
    public record Device(String fingerprint, byte[] publicKey, DeviceRole role, String department, String name,
                         Instant added) {
        public Device {
            publicKey = publicKey.clone();
        }

        @Override
        public byte[] publicKey() {
            return publicKey.clone();
        }

        public boolean guest() {
            return role == DeviceRole.RESTRICTED_GUEST;
        }

        /** {@code role=FULL_PEER department=FINANCE} */
        public String labels() {
            return "role=" + role + (department.isEmpty() ? "" : " department=" + department);
        }
    }

    private final Path file;
    private final ConcurrentHashMap<String, Device> devices = new ConcurrentHashMap<>();

    public TrustStore(Path file) throws IOException {
        this.file = Objects.requireNonNull(file, "file must not be null");
        load();
    }

    public Path file() {
        return file;
    }

    private void load() throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            if (f.length != 6) {
                LOG.log(System.Logger.Level.WARNING, "Ignoring an invalid line in {0}", file);
                continue;
            }
            try {
                byte[] pub = Base64.getDecoder().decode(f[4]);
                Optional<DeviceRole> role = DeviceRole.parse(f[1]);
                if (!FINGERPRINT.matcher(f[0]).matches() || role.isEmpty()
                        || !DeviceIdentity.fingerprintOf(pub).equals(f[0]) || !DeviceIdentity.validPublicKey(pub)) {
                    LOG.log(System.Logger.Level.WARNING, "Ignoring an invalid line in {0}", file);
                    continue;
                }
                devices.put(f[0], new Device(f[0], pub, role.get(), department(f[2]), name(f[3]),
                        Instant.ofEpochMilli(Long.parseLong(f[5]))));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Ignoring an invalid line in {0}", file);
            }
        }
    }

    private synchronized void save() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# Document Workbench trusted devices: fingerprint, role, department, name, public key, added");
        for (Device d : devices()) {
            lines.add(String.join("\t", d.fingerprint(), d.role().name(), d.department(), d.name(),
                    Base64.getEncoder().encodeToString(d.publicKey()), Long.toString(d.added().toEpochMilli())));
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temp, lines, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------------ queries

    public List<Device> devices() {
        List<Device> out = new ArrayList<>(devices.values());
        out.sort(Comparator.comparing(Device::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Device::fingerprint));
        return out;
    }

    /** The trusted device with exactly this public key (fingerprint and key bytes both match). */
    public Optional<Device> authenticate(byte[] publicKey) {
        Device d = devices.get(DeviceIdentity.fingerprintOf(publicKey));
        return d != null && Arrays.equals(d.publicKey, publicKey) ? Optional.of(d) : Optional.empty();
    }

    public Optional<Device> find(String fingerprint) {
        return fingerprint == null ? Optional.empty() : Optional.ofNullable(devices.get(fingerprint));
    }

    public boolean trusted(String fingerprint) {
        return find(fingerprint).isPresent();
    }

    /** By full fingerprint, a unique fingerprint prefix of 8+ characters, or a unique name (case-insensitive). */
    public Optional<Device> resolve(String token) {
        String t = token.strip();
        String lower = t.toLowerCase(Locale.ROOT);
        Device exact = devices.get(lower);
        if (exact != null) {
            return Optional.of(exact);
        }
        List<Device> byName = devices.values().stream().filter(d -> d.name().equalsIgnoreCase(t)).toList();
        if (byName.size() == 1) {
            return Optional.of(byName.getFirst());
        }
        if (byName.isEmpty() && lower.length() >= 8) {
            String prefix = lower.replace("-", "");
            List<Device> byPrefix = devices.values().stream().filter(d -> d.fingerprint().startsWith(prefix)).toList();
            if (byPrefix.size() == 1) {
                return Optional.of(byPrefix.getFirst());
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ changes

    /** Adds or replaces a device (pairing). */
    public Device add(byte[] publicKey, String name, DeviceRole role, String department) throws IOException {
        if (!DeviceIdentity.validPublicKey(publicKey)) {
            throw new IllegalArgumentException("not an Ed25519 public key");
        }
        String fp = DeviceIdentity.fingerprintOf(publicKey);
        Device d = new Device(fp, publicKey, Objects.requireNonNull(role, "role"), department(department), name(name),
                Instant.now());
        devices.put(fp, d);
        save();
        return d;
    }

    public boolean remove(String fingerprint) throws IOException {
        boolean removed = devices.remove(fingerprint) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    public Device setRole(String fingerprint, DeviceRole role) throws IOException {
        return update(fingerprint, d -> new Device(d.fingerprint(), d.publicKey, role, d.department(), d.name(), d.added()));
    }

    public Device setDepartment(String fingerprint, String department) throws IOException {
        String dept = department(department);
        return update(fingerprint, d -> new Device(d.fingerprint(), d.publicKey, d.role(), dept, d.name(), d.added()));
    }

    private synchronized Device update(String fingerprint, java.util.function.UnaryOperator<Device> change)
            throws IOException {
        Device current = devices.get(fingerprint);
        if (current == null) {
            throw new IllegalArgumentException("not a trusted device: " + fingerprint);
        }
        Device next = change.apply(current);
        devices.put(fingerprint, next);
        save();
        return next;
    }

    // ------------------------------------------------------------------ normalization

    /** {@code FINANCE}; empty for none. Upper case letters, digits, {@code _} and {@code -}, at most 32. */
    public static String department(String raw) {
        if (raw == null) {
            return "";
        }
        String d = raw.strip().toUpperCase(Locale.ROOT);
        if (d.startsWith("DEPARTMENT=")) {
            d = d.substring("DEPARTMENT=".length());
        } else if (d.startsWith("DEPT:") || d.startsWith("DEPT=")) {
            d = d.substring(5);
        }
        if (d.isEmpty() || d.equals("-")) {
            return "";
        }
        if (!DEPARTMENT.matcher(d).matches()) {
            throw new IllegalArgumentException("department must be 1-32 letters, digits, '_' or '-': " + raw);
        }
        return d;
    }

    /** A display name without tabs, line breaks or other control characters. */
    static String name(String raw) {
        if (raw == null) {
            return "device";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < MAX_NAME; i++) {
            char c = raw.charAt(i);
            if (!Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        String clean = sb.toString().strip();
        return clean.isEmpty() ? "device" : clean;
    }
}
