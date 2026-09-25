package org.example.p2p;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Zero-trust LAN mode: device identity, the trusted-device list, the pairing window and the rules that decide what a
 * device may see. Shared by {@link FileTransferService} (handshake, access control, pairing) and
 * {@link PeerDiscovery} (signed announcements, deaf to unpaired devices).
 *
 * <p>Default state is blind and deaf: a device that was never paired cannot complete a TCP session (the socket is
 * closed without an answer), its announcements are ignored and it never receives a catalog. Pairing needs a 6-digit
 * PIN created on an already trusted device ({@link #openPairing}); the PIN is valid for {@link #PAIRING_TTL} and for
 * exactly one attempt.</p>
 */
public final class ZeroTrust {

    /** How long a pairing PIN stays valid. */
    public static final Duration PAIRING_TTL = Duration.ofMinutes(5);
    private static final int MAX_SIGHTINGS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** An open pairing window: whoever proves this PIN first is added with this role and department. */
    public record PairingTicket(String pin, DeviceRole role, String department, Instant expires) {
        public boolean expired() {
            return Instant.now().isAfter(expires);
        }

        @Override
        public String toString() {
            return "PairingTicket[role=" + role + ", department=" + department + ", expires=" + expires + "]";
        }
    }

    /**
     * Outcome of a pairing attempt against this device's open PIN (called on the transfer thread: hand off, never
     * block). The UI shows the verification code next to the one on the new device.
     */
    public interface PairingListener {
        void paired(TrustStore.Device device, String verificationCode);

        /** The attempt failed (wrong PIN, bad key); the PIN is void. */
        default void failed(String reason) {
        }
    }

    /**
     * An unpaired device that announced itself (shown so an administrator can pair it; it is not a peer).
     *
     * @param transferPort the TCP port it announced (where a pairing PIN would be entered), 0 when unknown
     */
    public record Sighting(String fingerprint, String name, InetAddress address, int transferPort, Instant lastSeen) {
        /** {@code host:port} for pairing with it. */
        public String endpoint() {
            String host = address.getHostAddress();
            return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + transferPort;
        }
    }

    private final DeviceIdentity identity;
    private final TrustStore trust;
    private final AtomicReference<PairingTicket> ticket = new AtomicReference<>();
    private final ConcurrentHashMap<String, Sighting> sightings = new ConcurrentHashMap<>();
    private volatile String localName = "device";
    private final java.util.concurrent.CopyOnWriteArrayList<PairingListener> pairingListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public ZeroTrust(DeviceIdentity identity, TrustStore trust) {
        this.identity = Objects.requireNonNull(identity, "identity must not be null");
        this.trust = Objects.requireNonNull(trust, "trust must not be null");
    }

    /** {@code <home>/device.key} and {@code <home>/trusted-peers.tsv}. Blocking (disk, key generation). */
    public static ZeroTrust load(Path home, MachineKey machine) throws IOException {
        return new ZeroTrust(DeviceIdentity.loadOrCreate(home, machine), new TrustStore(home.resolve(TrustStore.FILE)));
    }

    public DeviceIdentity identity() {
        return identity;
    }

    public TrustStore trust() {
        return trust;
    }

    /** The name other devices store for this one when they pair with it (the node name). */
    public String localName() {
        return localName;
    }

    public ZeroTrust withLocalName(String name) {
        this.localName = TrustStore.name(name);
        return this;
    }

    // ------------------------------------------------------------------ pairing window

    /** Opens (or replaces) the pairing window and returns its PIN. */
    public PairingTicket openPairing(DeviceRole role, String department) {
        String pin = String.format("%06d", RANDOM.nextInt(1_000_000));
        PairingTicket t = new PairingTicket(pin, Objects.requireNonNull(role, "role"),
                TrustStore.department(department), Instant.now().plus(PAIRING_TTL));
        ticket.set(t);
        return t;
    }

    public void cancelPairing() {
        ticket.set(null);
    }

    /** The open, unexpired pairing window, if any. */
    public Optional<PairingTicket> pendingPairing() {
        PairingTicket t = ticket.get();
        return t == null || t.expired() ? Optional.empty() : Optional.of(t);
    }

    /**
     * Takes the pairing window for one attempt: whatever the attempt's outcome, the PIN cannot be tried again, so
     * guessing it online is impossible.
     */
    Optional<PairingTicket> takePairing() {
        PairingTicket t = ticket.getAndSet(null);
        return t == null || t.expired() ? Optional.empty() : Optional.of(t);
    }

    /** Registers a listener for pairings with this device's PIN; run the returned action to remove it. */
    public Runnable onPairing(PairingListener listener) {
        pairingListeners.add(Objects.requireNonNull(listener, "listener"));
        return () -> pairingListeners.remove(listener);
    }

    void firePaired(TrustStore.Device device, String code) {
        for (PairingListener l : pairingListeners) {
            try {
                l.paired(device, code);
            } catch (RuntimeException e) {
                System.getLogger(ZeroTrust.class.getName()).log(System.Logger.Level.WARNING, "Pairing listener failed", e);
            }
        }
    }

    void firePairingFailed(String reason) {
        for (PairingListener l : pairingListeners) {
            try {
                l.failed(reason);
            } catch (RuntimeException e) {
                System.getLogger(ZeroTrust.class.getName()).log(System.Logger.Level.WARNING, "Pairing listener failed", e);
            }
        }
    }

    // ------------------------------------------------------------------ access rules

    /** Whether {@code device} may see (in its catalog) and pull this document from {@code store}. */
    public boolean visible(ContentStore store, String sha256, TrustStore.Device device) {
        if (!store.isShared(sha256)) {
            return false;
        }
        AccessList acl = store.acl();
        if (device.guest()) {
            return acl.permits(sha256, device);
        }
        return !acl.restricted(sha256) || acl.permits(sha256, device);
    }

    /** The catalog one device receives: guests see only what was granted to them. */
    public Set<String> catalogFor(ContentStore store, TrustStore.Device device) {
        Set<String> out = new TreeSet<>();
        if (device.guest()) {
            for (String sha : store.acl().grantedTo(device)) {
                if (store.isShared(sha) && store.has(sha)) {
                    out.add(sha);
                }
            }
            return out;
        }
        for (String sha : store.sharedHashes()) {
            if (visible(store, sha, device)) {
                out.add(sha);
            }
        }
        return out;
    }

    /** The access-list entry for granting one document to a device: one-shot for guests. */
    public static String grantEntry(TrustStore.Device device) {
        return device.guest() ? "once:" + device.fingerprint() : device.fingerprint();
    }

    /** Removes a device from the trust list and from every access list of {@code store}. */
    public boolean forget(String fingerprint, ContentStore store) throws IOException {
        boolean removed = trust.remove(fingerprint);
        if (store != null) {
            store.acl().forget(fingerprint);
        }
        return removed;
    }

    // ------------------------------------------------------------------ unpaired devices seen on the network

    void sighted(String fingerprint, String name, InetAddress address, int transferPort) {
        if (sightings.size() >= MAX_SIGHTINGS && !sightings.containsKey(fingerprint)) {
            Instant cutoff = Instant.now().minus(Duration.ofMinutes(10));
            sightings.values().removeIf(s -> s.lastSeen().isBefore(cutoff));
            if (sightings.size() >= MAX_SIGHTINGS) {
                return;
            }
        }
        sightings.put(fingerprint, new Sighting(fingerprint, TrustStore.name(name), address,
                transferPort >= 1 && transferPort <= 65_535 ? transferPort : 0, Instant.now()));
    }

    /** Unpaired devices announced within the last ten minutes (newest first). */
    public List<Sighting> unpaired() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(10));
        List<Sighting> out = new ArrayList<>();
        for (Sighting s : sightings.values()) {
            if (s.lastSeen().isAfter(cutoff) && !trust.trusted(s.fingerprint())) {
                out.add(s);
            }
        }
        out.sort(Comparator.comparing(Sighting::lastSeen).reversed());
        return out;
    }
}
