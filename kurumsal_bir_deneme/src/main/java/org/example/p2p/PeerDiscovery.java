package org.example.p2p;

import org.example.util.Hashing;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.StandardSocketOptions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Zero-cloud LAN presence and catalog exchange over UDP multicast (admin-scoped group, TTL 1).
 *
 * <p>Wire format: small UTF-8 datagrams ({@code DWB1} header, {@code key=value} lines, trailing {@code mac=}
 * HMAC line in legacy mode, trailing {@code sig=} Ed25519 line in zero-trust mode). Message types:</p>
 * <ul>
 *   <li>{@code ANN} – presence: node id, name, TCP transfer port, catalog fingerprint, document count.</li>
 *   <li>{@code HASH} – one part of the node's SHA-256 catalog (16 hashes per datagram).</li>
 *   <li>{@code BYE} – graceful departure.</li>
 * </ul>
 * <p>Announcements repeat every interval; the full catalog is re-broadcast when it changes and periodically for
 * late joiners. New peers get an immediate unicast reply, and optional unicast seeds make the protocol work on
 * networks that block multicast. Every datagram also goes to the IPv4 subnet broadcast address of each joined
 * interface (same payload, same port), because many consumer Wi-Fi routers drop multicast between clients while
 * still forwarding broadcast. Peers silent for longer than the TTL are dropped.</p>
 *
 * <p>Zero-trust mode ({@link ZeroTrust}): the node id is the fingerprint of the device key, every announcement
 * carries the public key ({@code pk=}) and an Ed25519 signature, and only announcements of devices in the
 * trusted-peers list are heard (others are only noted as {@linkplain ZeroTrust#unpaired unpaired sightings}, never
 * answered). No catalog is ever multicast: {@code HASH} datagrams are neither sent nor accepted, {@code ANN} carries
 * no document count, and its catalog version is salted per process. When a trusted peer's version changes, its
 * catalog is fetched over the authenticated transfer channel ({@link CatalogSource}), filtered by that peer for this
 * device.</p>
 */
public final class PeerDiscovery implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(PeerDiscovery.class.getName());

    public static final String DEFAULT_GROUP = "239.255.77.77";
    public static final int DEFAULT_PORT = 47777;

    private static final String MAGIC = "DWB1";
    private static final int MAX_DATAGRAM = 1_400;
    private static final int HASHES_PER_PACKET = 16;
    private static final int MAX_FIELDS = 16;
    private static final int MAX_ADVERTISED = 100_000;
    private static final long MAX_CLOCK_SKEW_MILLIS = 120_000;
    private static final int FULL_CATALOG_EVERY_TICKS = 6;
    private static final Pattern NODE_ID = Pattern.compile("[0-9a-f]{16}|[0-9a-f]{64}");
    /** Zero-trust mode: a failed catalog fetch is retried after this delay. */
    private static final long CATALOG_RETRY_MILLIS = 30_000;
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{16}");

    /** Discovery settings. */
    public record Config(String nodeId, String nodeName, InetAddress group, int port, int transferPort,
                         Duration announceInterval, Duration peerTtl, List<InetSocketAddress> seeds) {
        public Config {
            if (!NODE_ID.matcher(Objects.requireNonNull(nodeId, "nodeId")).matches()) {
                throw new IllegalArgumentException("nodeId must be 16 (or, for a device fingerprint, 64) lowercase hex"
                        + " characters");
            }
            if (!Objects.requireNonNull(group, "group").isMulticastAddress()) {
                throw new IllegalArgumentException(group + " is not a multicast address");
            }
            nodeName = sanitize(nodeName);
            seeds = List.copyOf(seeds);
        }
    }

    /** Zero-trust mode: fetches the catalog a trusted peer shows this device (over the authenticated channel). */
    @FunctionalInterface
    public interface CatalogSource {
        Set<String> fetch(PeerInfo peer) throws IOException;
    }

    /** Membership callbacks (invoked on the discovery thread; keep them short). */
    public interface Listener {
        default void peerJoined(PeerInfo peer) {
        }

        default void peerLeft(PeerInfo peer) {
        }

        default void catalogUpdated(PeerInfo peer) {
        }
    }

    private static final class PeerState {
        final String nodeId;
        String name;
        InetAddress address;
        InetSocketAddress discoveryEndpoint;
        int transferPort;
        Instant lastSeen;
        String catalogVersion = "";
        int advertised;
        Set<String> hashes = Set.of();
        String pendingVersion = "";
        int pendingParts;
        final Set<Integer> receivedParts = new HashSet<>();
        final Set<String> pendingHashes = new HashSet<>();
        /** Zero-trust mode: a catalog fetch is running / the time before which a failed one is not retried. */
        boolean fetching;
        long retryAt;

        PeerState(String nodeId) {
            this.nodeId = nodeId;
        }

        synchronized PeerInfo snapshot() {
            return new PeerInfo(nodeId, name, address, transferPort, lastSeen, catalogVersion, advertised, hashes);
        }
    }

    private final Config config;
    private final LanSecurity security;
    /** Zero-trust context; {@code null} in legacy mode. */
    private final ZeroTrust trust;
    private final CatalogSource catalogs;
    /** Zero-trust mode: bumped by {@link #catalogChanged()} so access-list changes reach peers too. */
    private final java.util.concurrent.atomic.AtomicLong catalogGeneration = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean collisionReported;
    private final Supplier<Set<String>> localHashes;
    private final Listener listener;
    private final ConcurrentHashMap<String, PeerState> peers = new ConcurrentHashMap<>();
    /** MACs of accepted signed datagrams (authenticated mode) with their arrival time, for replay rejection. */
    private final ConcurrentHashMap<String, Long> seenMacs = new ConcurrentHashMap<>();
    private volatile long lastMacPrune;
    private static final int MAX_SEEN_MACS = 50_000;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("dwb-discovery-timer").daemon(true).factory());
    private final List<NetworkInterface> joined = new ArrayList<>();
    /** Subnet broadcast endpoints of the joined non-loopback interfaces (IPv4 only; never leaves the segment). */
    private final List<InetSocketAddress> broadcasts = new ArrayList<>();
    private MulticastSocket socket;
    private Thread receiver;
    private volatile boolean running;
    private volatile String advertisedVersion = "";
    private long ticks;
    private long lastReplyNanos;
    /**
     * This node's id: the configured one until another machine turns out to use it too (a cloned disk image or a
     * copied workspace), then a fresh random one. Without that the two would drop each other's datagrams as echoes.
     */
    private volatile String nodeId;
    /** Random per process; sent in {@code ANN} as {@code in=} so an echo can be told apart from a clone. */
    private final String instance = Hashing.hex(LanSecurity.nonce(8));
    private volatile long lastIdChange;

    /** Legacy mode: open, or HMAC-authenticated with {@code DWB_SECRET}. */
    public PeerDiscovery(Config config, LanSecurity security, Supplier<Set<String>> localHashes, Listener listener) {
        this(config, security, null, null, localHashes, listener);
    }

    /**
     * Zero-trust mode: signed announcements, trusted devices only, catalogs over {@code catalogs}. The configured
     * node id must be this device's fingerprint.
     */
    public PeerDiscovery(Config config, ZeroTrust trust, CatalogSource catalogs, Supplier<Set<String>> localHashes,
                         Listener listener) {
        this(config, LanSecurity.open(), Objects.requireNonNull(trust, "trust must not be null"),
                Objects.requireNonNull(catalogs, "catalogs must not be null"), localHashes, listener);
        if (!config.nodeId().equals(trust.identity().fingerprint())) {
            throw new IllegalArgumentException("in zero-trust mode the node id is the device fingerprint");
        }
    }

    private PeerDiscovery(Config config, LanSecurity security, ZeroTrust trust, CatalogSource catalogs,
                          Supplier<Set<String>> localHashes, Listener listener) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.security = Objects.requireNonNull(security, "security must not be null");
        this.trust = trust;
        this.catalogs = catalogs;
        this.localHashes = Objects.requireNonNull(localHashes, "localHashes must not be null");
        this.listener = listener == null ? new Listener() { } : listener;
        this.nodeId = config.nodeId();
    }

    /** The id this node currently announces (differs from the configured one after an id collision). */
    public String nodeId() {
        return nodeId;
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        MulticastSocket s = new MulticastSocket(null);
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(config.port()));
        s.setTimeToLive(1);
        s.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, Boolean.TRUE);
        InetSocketAddress group = new InetSocketAddress(config.group(), 0);
        for (NetworkInterface ni : eligibleInterfaces()) {
            try {
                s.joinGroup(group, ni);
                joined.add(ni);
            } catch (IOException e) {
                LOG.log(System.Logger.Level.DEBUG, "Cannot join {0} on {1}: {2}", config.group(), ni.getName(), e.getMessage());
            }
        }
        try {
            s.setBroadcast(true);
            for (NetworkInterface ni : joined) {
                if (ni.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress broadcast = ia.getBroadcast();
                    if (broadcast != null) {
                        broadcasts.add(new InetSocketAddress(broadcast, config.port()));
                    }
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "Subnet broadcast unavailable: {0}", e.getMessage());
        }
        if (joined.isEmpty() && config.seeds().isEmpty()) {
            LOG.log(System.Logger.Level.WARNING, "No multicast-capable interface and no seeds; discovery is passive");
        }
        socket = s;
        running = true;
        receiver = Thread.ofVirtual().name("dwb-discovery-rx").start(this::receiveLoop);
        long every = Math.max(1_000, config.announceInterval().toMillis());
        timer.scheduleAtFixedRate(this::tick, 0, every, TimeUnit.MILLISECONDS);
    }

    public List<String> interfaces() {
        return joined.stream().map(NetworkInterface::getName).toList();
    }

    /** Call whenever the local catalog changed; pushes the new catalog immediately. */
    public void catalogChanged() {
        catalogGeneration.incrementAndGet();
        if (running) {
            timer.execute(() -> broadcast(true));
        }
    }

    public List<PeerInfo> peers() {
        List<PeerInfo> out = new ArrayList<>();
        for (PeerState state : peers.values()) {
            out.add(state.snapshot());
        }
        out.sort(Comparator.comparing(PeerInfo::name, String.CASE_INSENSITIVE_ORDER).thenComparing(PeerInfo::nodeId));
        return out;
    }

    /** Finds a peer by node id (or unique prefix of 4+ characters) or by name (case-insensitive). */
    public Optional<PeerInfo> find(String token) {
        String t = token.strip();
        List<PeerInfo> all = peers();
        for (PeerInfo p : all) {
            if (p.nodeId().equals(t)) {
                return Optional.of(p);
            }
        }
        // A name shared by several nodes is ambiguous: never pick one of them silently.
        List<PeerInfo> byName = all.stream().filter(p -> p.name().equalsIgnoreCase(t)).toList();
        if (byName.size() == 1) {
            return Optional.of(byName.getFirst());
        }
        if (byName.size() > 1) {
            return Optional.empty();
        }
        if (t.length() >= 4) {
            List<PeerInfo> byPrefix = all.stream().filter(p -> p.nodeId().startsWith(t.toLowerCase(Locale.ROOT))).toList();
            if (byPrefix.size() == 1) {
                return Optional.of(byPrefix.getFirst());
            }
        }
        return Optional.empty();
    }

    /** Zero-trust mode: fetches every trusted peer's catalog again (e.g. {@code lan --sync}); no-op otherwise. */
    public void refreshCatalogs() {
        if (trust == null) {
            return;
        }
        for (PeerState state : peers.values()) {
            synchronized (state) {
                state.retryAt = 0;
            }
            fetchCatalog(state, true);
        }
    }

    /** Peers advertising the given document. */
    public List<PeerInfo> holders(String sha256) {
        return peers().stream().filter(p -> p.hashes().contains(sha256)).toList();
    }

    // ------------------------------------------------------------------ sending

    private void tick() {
        try {
            Instant cutoff = Instant.now().minus(config.peerTtl());
            for (PeerState state : peers.values()) {
                if (state.snapshot().lastSeen().isBefore(cutoff) && peers.remove(state.nodeId, state)) {
                    listener.peerLeft(state.snapshot());
                }
            }
            broadcast(ticks++ % FULL_CATALOG_EVERY_TICKS == 0);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Discovery tick failed", e);
        }
    }

    private void broadcast(boolean forceCatalog) {
        List<String> hashes = new ArrayList<>(localHashes.get());
        Collections.sort(hashes);
        if (trust != null) {
            // Presence only: the catalog is never multicast, and the version says nothing about the content.
            send(announcement(saltedVersion(hashes), 0), null);
            return;
        }
        String version = fingerprint(hashes);
        boolean changed = !version.equals(advertisedVersion);
        advertisedVersion = version;
        send(announcement(version, hashes.size()), null);
        if (changed || forceCatalog) {
            for (byte[] part : catalogParts(version, hashes)) {
                send(part, null);
            }
        }
    }

    private byte[] announcement(String version, int count) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("t", "ANN");
        f.put("id", nodeId);
        f.put("name", config.nodeName());
        f.put("tp", Integer.toString(config.transferPort()));
        f.put("cv", version);
        if (trust == null) {
            f.put("cnt", Integer.toString(count));
        }
        f.put("ts", Long.toString(System.currentTimeMillis()));
        f.put("in", instance);
        if (trust != null) {
            f.put("pk", java.util.Base64.getEncoder().encodeToString(trust.identity().publicKey()));
        }
        return encode(f);
    }

    /** Zero-trust catalog version: changes with the content and every {@link #catalogChanged()}, unlinkable to it. */
    private String saltedVersion(List<String> sortedHashes) {
        List<String> salted = new ArrayList<>(sortedHashes);
        salted.add(instance + ":" + catalogGeneration.get());
        return fingerprint(salted);
    }

    private List<byte[]> catalogParts(String version, List<String> hashes) {
        List<byte[]> out = new ArrayList<>();
        int parts = (hashes.size() + HASHES_PER_PACKET - 1) / HASHES_PER_PACKET;
        for (int part = 0; part < parts; part++) {
            List<String> slice = hashes.subList(part * HASHES_PER_PACKET,
                    Math.min(hashes.size(), (part + 1) * HASHES_PER_PACKET));
            Map<String, String> f = new LinkedHashMap<>();
            f.put("t", "HASH");
            f.put("id", nodeId);
            f.put("cv", version);
            f.put("part", Integer.toString(part));
            f.put("parts", Integer.toString(parts));
            f.put("ts", Long.toString(System.currentTimeMillis()));
            f.put("h", String.join(",", slice));
            out.add(encode(f));
        }
        return out;
    }

    /** Sends to the multicast group on every joined interface, to seeds and to known peers (unicast). */
    private synchronized void send(byte[] payload, InetSocketAddress onlyTo) {
        MulticastSocket s = socket;
        if (s == null || s.isClosed()) {
            return;
        }
        if (onlyTo != null) {
            unicast(s, payload, onlyTo);
            return;
        }
        InetSocketAddress group = new InetSocketAddress(config.group(), config.port());
        for (NetworkInterface ni : joined) {
            try {
                s.setOption(StandardSocketOptions.IP_MULTICAST_IF, ni);
                s.send(new DatagramPacket(payload, payload.length, group));
            } catch (IOException e) {
                LOG.log(System.Logger.Level.DEBUG, "Multicast send on {0} failed: {1}", ni.getName(), e.getMessage());
            }
        }
        Set<InetSocketAddress> targets = new HashSet<>(config.seeds());
        targets.addAll(broadcasts);
        for (PeerState state : peers.values()) {
            InetSocketAddress endpoint;
            synchronized (state) {
                endpoint = state.discoveryEndpoint;
            }
            if (endpoint != null) {
                targets.add(endpoint);
            }
        }
        for (InetSocketAddress target : targets) {
            unicast(s, payload, target);
        }
    }

    private static void unicast(MulticastSocket s, byte[] payload, InetSocketAddress target) {
        try {
            if (!target.isUnresolved()) {
                s.send(new DatagramPacket(payload, payload.length, target));
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "Unicast send to {0} failed: {1}", target, e.getMessage());
        }
    }

    private byte[] encode(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder(256).append(MAGIC).append('\n');
        fields.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        String mac = trust != null ? "sig=" + Hashing.hex(trust.identity().sign(body)) + "\n"
                : "mac=" + Hashing.hex(security.mac(body)) + "\n";
        byte[] tail = mac.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[body.length + tail.length];
        System.arraycopy(body, 0, out, 0, body.length);
        System.arraycopy(tail, 0, out, body.length, tail.length);
        if (out.length > MAX_DATAGRAM) {
            throw new IllegalStateException("Discovery datagram too large: " + out.length);
        }
        return out;
    }

    // ------------------------------------------------------------------ receiving

    private void receiveLoop() {
        byte[] buffer = new byte[2_048];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (SocketException e) {
                if (running) {
                    LOG.log(System.Logger.Level.WARNING, "Discovery socket error: {0}", e.getMessage());
                }
                return;
            } catch (IOException e) {
                LOG.log(System.Logger.Level.DEBUG, "Discovery receive failed: {0}", e.getMessage());
                continue;
            }
            try {
                handle(packet.getData(), packet.getLength(), (InetSocketAddress) packet.getSocketAddress());
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "Dropped malformed datagram from {0}: {1}",
                        packet.getSocketAddress(), e.getMessage());
            }
        }
    }

    private void handle(byte[] data, int length, InetSocketAddress from) {
        if (length > MAX_DATAGRAM) {
            return;
        }
        String text = new String(data, 0, length, StandardCharsets.UTF_8);
        if (!text.startsWith(MAGIC + "\n")) {
            return;
        }
        if (trust != null) {
            handleSigned(text, from);
            return;
        }
        int macLine = text.lastIndexOf("\nmac=");
        if (macLine < 0) {
            return;
        }
        byte[] body = text.substring(0, macLine + 1).getBytes(StandardCharsets.UTF_8);
        String macHex = text.substring(macLine + 5).strip();
        if (macHex.length() != LanSecurity.MAC_BYTES * 2 || !security.verify(Hashing.fromHex(macHex), body)) {
            return;
        }
        Map<String, String> f = new LinkedHashMap<>();
        for (String line : text.substring(MAGIC.length() + 1, macLine).split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0 || f.size() >= MAX_FIELDS) {
                return;
            }
            f.put(line.substring(0, eq), line.substring(eq + 1));
        }
        String id = f.getOrDefault("id", "");
        if (!NODE_ID.matcher(id).matches()) {
            return;
        }
        if (id.equals(nodeId)) {
            String in = f.get("in");
            if ("ANN".equals(f.get("t")) && in != null && !in.equals(instance)) {
                resolveIdCollision(from);
            }
            return; // our own datagram looped back (or a clone's, until the new id is out)
        }
        if (security.enabled()) {
            long now = System.currentTimeMillis();
            long ts = parseLong(f.get("ts"), -1);
            if (Math.abs(now - ts) > MAX_CLOCK_SKEW_MILLIS) {
                return;
            }
            // A signed datagram is accepted once. Within the clock-skew window a captured announcement could
            // otherwise be replayed from another host to redirect this peer's address, or a BYE to evict it.
            // Legitimate copies of the same datagram (several interfaces, multicast + unicast) carry nothing new.
            if (seenMacs.putIfAbsent(macHex.toLowerCase(Locale.ROOT), now) != null) {
                return;
            }
            if (seenMacs.size() > MAX_SEEN_MACS || now - lastMacPrune > MAX_CLOCK_SKEW_MILLIS) {
                lastMacPrune = now;
                seenMacs.values().removeIf(at -> now - at > 2 * MAX_CLOCK_SKEW_MILLIS);
            }
        }
        switch (f.getOrDefault("t", "")) {
            case "ANN" -> onAnnounce(id, f, from);
            case "HASH" -> onHashes(id, f, from);
            case "BYE" -> {
                PeerState gone = peers.remove(id);
                if (gone != null) {
                    listener.peerLeft(gone.snapshot());
                }
            }
            default -> {
                // unknown message type from a newer protocol revision
            }
        }
    }

    /**
     * Zero-trust datagram: {@code sig=} must be a valid Ed25519 signature by the key in {@code pk=}, whose fingerprint
     * is the {@code id=}; then time stamp and replay checks. Devices outside the trusted-peers list are noted as
     * sightings and otherwise ignored: no peer entry, no reply, no catalog.
     */
    private void handleSigned(String text, InetSocketAddress from) {
        int sigLine = text.lastIndexOf("\nsig=");
        if (sigLine < 0) {
            return;
        }
        byte[] body = text.substring(0, sigLine + 1).getBytes(StandardCharsets.UTF_8);
        String sigHex = text.substring(sigLine + 5).strip();
        if (sigHex.length() != DeviceIdentity.SIGNATURE_BYTES * 2 || !sigHex.matches("[0-9a-fA-F]+")) {
            return;
        }
        Map<String, String> f = new LinkedHashMap<>();
        for (String line : text.substring(MAGIC.length() + 1, sigLine).split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0 || f.size() >= MAX_FIELDS) {
                return;
            }
            f.put(line.substring(0, eq), line.substring(eq + 1));
        }
        String id = f.getOrDefault("id", "");
        if (id.equals(nodeId)) {
            String in = f.get("in");
            if (in != null && !in.equals(instance) && !collisionReported) {
                collisionReported = true;
                LOG.log(System.Logger.Level.WARNING, "Another process at {0} uses this device key ({1}); run one node"
                        + " per key directory", from.getAddress().getHostAddress(), DeviceIdentity.display(nodeId));
            }
            return; // our own datagram looped back
        }
        byte[] pk;
        try {
            pk = java.util.Base64.getDecoder().decode(f.getOrDefault("pk", ""));
        } catch (IllegalArgumentException e) {
            return;
        }
        if (id.length() != 64 || pk.length == 0 || !DeviceIdentity.fingerprintOf(pk).equals(id)
                || !DeviceIdentity.verify(pk, Hashing.fromHex(sigHex), body)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (Math.abs(now - parseLong(f.get("ts"), -1)) > MAX_CLOCK_SKEW_MILLIS) {
            return;
        }
        if (seenMacs.putIfAbsent(sigHex.toLowerCase(Locale.ROOT), now) != null) {
            return; // replayed
        }
        if (seenMacs.size() > MAX_SEEN_MACS || now - lastMacPrune > MAX_CLOCK_SKEW_MILLIS) {
            lastMacPrune = now;
            seenMacs.values().removeIf(at -> now - at > 2 * MAX_CLOCK_SKEW_MILLIS);
        }
        if (trust.trust().authenticate(pk).isEmpty()) {
            if ("ANN".equals(f.get("t"))) {
                trust.sighted(id, sanitize(f.get("name")), from.getAddress());
            }
            return; // blind and deaf to devices that were never paired
        }
        switch (f.getOrDefault("t", "")) {
            case "ANN" -> onAnnounce(id, f, from);
            case "BYE" -> {
                PeerState gone = peers.remove(id);
                if (gone != null) {
                    listener.peerLeft(gone.snapshot());
                }
            }
            default -> {
                // HASH is never accepted in zero-trust mode; unknown types come from newer revisions
            }
        }
    }

    /**
     * Zero-trust mode: fetches a trusted peer's catalog over the authenticated channel on a virtual thread (one at a
     * time per peer, failures retried after {@link #CATALOG_RETRY_MILLIS}).
     */
    private void fetchCatalog(PeerState state, boolean force) {
        String version;
        synchronized (state) {
            long now = System.currentTimeMillis();
            if (state.fetching || (!force && now < state.retryAt) || state.pendingVersion.isEmpty()
                    || (!force && state.pendingVersion.equals(state.catalogVersion))) {
                return;
            }
            state.fetching = true;
            version = state.pendingVersion;
        }
        PeerInfo peer = state.snapshot();
        Thread.ofVirtual().name("dwb-discovery-catalog").start(() -> {
            boolean completed = false;
            try {
                Set<String> hashes = new HashSet<>();
                for (String h : catalogs.fetch(peer)) {
                    if (Hashing.isSha256Hex(h) && hashes.size() < MAX_ADVERTISED) {
                        hashes.add(h);
                    }
                }
                synchronized (state) {
                    state.hashes = Set.copyOf(hashes);
                    state.advertised = hashes.size();
                    state.catalogVersion = version;
                    state.retryAt = 0;
                }
                completed = true;
            } catch (IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "Catalog of {0} unavailable: {1}", peer.name(), e.getMessage());
                synchronized (state) {
                    state.retryAt = System.currentTimeMillis() + CATALOG_RETRY_MILLIS;
                }
            } finally {
                synchronized (state) {
                    state.fetching = false;
                }
            }
            if (completed && running && peers.get(state.nodeId) == state) {
                listener.catalogUpdated(state.snapshot());
            }
        });
    }

    /**
     * Another process announces our id: switch to a fresh random id and announce it at once. Both sides usually
     * switch, which is harmless; the old id simply expires from the peers' lists after the TTL.
     */
    private void resolveIdCollision(InetSocketAddress from) {
        long now = System.nanoTime();
        synchronized (this) {
            if (lastIdChange != 0 && now - lastIdChange < TimeUnit.SECONDS.toNanos(10)) {
                return; // rate limit: stragglers of the old id are still in flight
            }
            lastIdChange = now;
            String old = nodeId;
            nodeId = Hashing.hex(LanSecurity.nonce(8));
            LOG.log(System.Logger.Level.WARNING, "Node id {0} is also used by {1} (cloned machine or copied"
                    + " workspace); this node now announces as {2}", old, from.getAddress().getHostAddress(), nodeId);
        }
        if (running) {
            timer.execute(() -> broadcast(true));
        }
    }

    private void onAnnounce(String id, Map<String, String> f, InetSocketAddress from) {
        int transferPort = (int) parseLong(f.get("tp"), -1);
        int count = trust != null ? 0 : (int) parseLong(f.get("cnt"), -1);
        String version = f.getOrDefault("cv", "");
        if (transferPort < 1 || transferPort > 65_535 || count < 0 || count > MAX_ADVERTISED
                || !FINGERPRINT.matcher(version).matches()) {
            return;
        }
        boolean[] isNew = {false};
        PeerState state = peers.computeIfAbsent(id, key -> {
            isNew[0] = true;
            return new PeerState(key);
        });
        synchronized (state) {
            state.name = sanitize(f.get("name"));
            state.address = from.getAddress();
            state.discoveryEndpoint = from;
            state.transferPort = transferPort;
            state.lastSeen = Instant.now();
            if (trust != null) {
                state.pendingVersion = version; // the catalog itself comes from fetchCatalog
            } else {
                state.advertised = count;
                if (count == 0 && !version.equals(state.catalogVersion)) {
                    state.hashes = Set.of();
                    state.catalogVersion = version;
                }
            }
        }
        if (isNew[0]) {
            listener.peerJoined(state.snapshot());
            replyTo(from);
        }
        if (trust != null) {
            fetchCatalog(state, false);
        }
    }

    /** Lets a newcomer learn about us without waiting for the next tick (rate limited). */
    private void replyTo(InetSocketAddress newcomer) {
        long now = System.nanoTime();
        synchronized (this) {
            if (now - lastReplyNanos < TimeUnit.MILLISECONDS.toNanos(500)) {
                return;
            }
            lastReplyNanos = now;
        }
        timer.execute(() -> {
            List<String> hashes = new ArrayList<>(localHashes.get());
            Collections.sort(hashes);
            if (trust != null) {
                send(announcement(saltedVersion(hashes), 0), newcomer);
                return;
            }
            String version = fingerprint(hashes);
            send(announcement(version, hashes.size()), newcomer);
            for (byte[] part : catalogParts(version, hashes)) {
                send(part, newcomer);
            }
        });
    }

    private void onHashes(String id, Map<String, String> f, InetSocketAddress from) {
        PeerState state = peers.get(id);
        if (state == null) {
            return;
        }
        String version = f.getOrDefault("cv", "");
        int part = (int) parseLong(f.get("part"), -1);
        int parts = (int) parseLong(f.get("parts"), -1);
        if (!FINGERPRINT.matcher(version).matches() || parts < 1 || part < 0 || part >= parts
                || parts > MAX_ADVERTISED / HASHES_PER_PACKET + 1) {
            return;
        }
        List<String> received = new ArrayList<>();
        for (String h : f.getOrDefault("h", "").split(",")) {
            if (Hashing.isSha256Hex(h)) {
                received.add(h);
            }
        }
        boolean completed = false;
        synchronized (state) {
            state.lastSeen = Instant.now();
            if (version.equals(state.catalogVersion)) {
                return;
            }
            if (!version.equals(state.pendingVersion)) {
                state.pendingVersion = version;
                state.pendingParts = parts;
                state.receivedParts.clear();
                state.pendingHashes.clear();
            }
            if (state.receivedParts.add(part)) {
                state.pendingHashes.addAll(received);
            }
            if (state.receivedParts.size() == state.pendingParts) {
                state.hashes = Set.copyOf(state.pendingHashes);
                state.catalogVersion = version;
                state.pendingVersion = "";
                state.receivedParts.clear();
                state.pendingHashes.clear();
                completed = true;
            }
        }
        if (completed) {
            listener.catalogUpdated(state.snapshot());
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public synchronized void close() {
        if (!running) {
            return;
        }
        Map<String, String> bye = new LinkedHashMap<>();
        bye.put("t", "BYE");
        bye.put("id", nodeId);
        bye.put("ts", Long.toString(System.currentTimeMillis()));
        send(encode(bye), null);
        running = false;
        timer.shutdownNow();
        InetSocketAddress group = new InetSocketAddress(config.group(), 0);
        for (NetworkInterface ni : joined) {
            try {
                socket.leaveGroup(group, ni);
            } catch (IOException ignored) {
                // socket is closing anyway
            }
        }
        socket.close();
        broadcasts.clear();
        if (receiver != null) {
            receiver.interrupt();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static List<NetworkInterface> eligibleInterfaces() throws SocketException {
        List<NetworkInterface> out = new ArrayList<>();
        List<NetworkInterface> loopbacks = new ArrayList<>();
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || !ni.supportsMulticast() || ni.isPointToPoint() || !ni.getInetAddresses().hasMoreElements()) {
                continue;
            }
            (ni.isLoopback() ? loopbacks : out).add(ni);
        }
        return out.isEmpty() ? loopbacks : out;
    }

    /** Stable 64-bit fingerprint of a sorted hash list. */
    static String fingerprint(List<String> sortedHashes) {
        MessageDigest digest = Hashing.sha256();
        for (String h : sortedHashes) {
            digest.update(h.getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) ',');
        }
        return Hashing.hex(digest.digest()).substring(0, 16);
    }

    private static long parseLong(String value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String sanitize(String name) {
        if (name == null || name.isBlank()) {
            return "node";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length() && sb.length() < 64; i++) {
            char c = name.charAt(i);
            if (!Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        String clean = sb.toString().strip();
        return clean.isEmpty() ? "node" : clean;
    }
}
