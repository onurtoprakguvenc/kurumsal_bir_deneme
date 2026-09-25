package org.example.p2p;

import org.example.core.IngestionException;
import org.example.core.DocumentIngestor.IngestResult;
import org.example.ingest.DocumentParser;
import org.example.model.BinaryAsset;
import org.example.model.ContentKind;
import org.example.model.DocumentRecord;
import org.example.p2p.ContentStore.StoredFile;
import org.example.p2p.FileTransferService.TransferResult;
import org.example.state.Subscription;
import org.example.util.Hashing;
import org.example.workbench.WorkbenchController;
import org.example.workbench.WorkbenchController.WorkbenchEvent;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Keeps one project's index in sync with the other Document Workbench nodes on the local network.
 *
 * <p>It is the glue between the existing LAN layer and a project's ingest pipeline; it adds no protocol of its own,
 * so desktop nodes and {@code dwb-cli} nodes see each other:</p>
 * <ul>
 *   <li><b>Announce</b>: every local document the project indexes is registered in a {@link ContentStore} (in place,
 *       no copy) and {@link PeerDiscovery} multicasts/broadcasts the new SHA-256 catalog at once.</li>
 *   <li><b>Fetch</b>: when a peer's catalog lists content this project does not index, a single background worker
 *       pulls it over {@link FileTransferService} (SHA-256 verified, resumable), moves it to
 *       {@code <project>/lan-inbox/<peer>/<name>} and ingests that path through
 *       {@link WorkbenchController#ingest(Path)} — the same pipeline as drag &amp; drop, Ctrl+O and {@code > add}, so
 *       the document lands in the inverted index and the UI refreshes through the ordinary {@code Ingested} event.</li>
 *   <li><b>Decline</b>: a document the user removes from this index is remembered ({@code lan/declined.txt}) and never
 *       fetched again, so a sync can not resurrect it; adding the file again by hand lifts the decline.</li>
 * </ul>
 *
 * <p>Nothing here runs on the caller's thread except bookkeeping: sockets, hashing, moves and ingestion happen on
 * virtual threads, one transfer at a time, so heap use stays at a few 64 KB buffers plus one extraction. Everything
 * stays on the local segment (multicast TTL 1, subnet broadcast, optional unicast seeds); there is no relay.</p>
 */
public final class LanSyncService implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(LanSyncService.class.getName());

    /** Store directory inside the project: objects, partial downloads, declined list. */
    public static final String STORE_DIR = "lan";
    /** Received documents are written below this project sub-folder, one folder per peer. */
    public static final String INBOX_DIR = "lan-inbox";
    static final String DECLINED_FILE = "declined.txt";

    /**
     * Largest single file background sync fetches or accepts (64 GiB, the same ceiling as a {@code dwb-cli} node, so
     * text documents and multi-gigabyte media sync alike). Memory use does not depend on it: bytes stream through
     * fixed 64 KiB buffers; free disk space is checked per transfer.
     */
    public static final long MAX_AUTO_FETCH_BYTES = 64L * 1024 * 1024 * 1024;
    /** Default cap for one received file; kept as the name existing callers use. */
    public static final long DEFAULT_MAX_FILE_BYTES = MAX_AUTO_FETCH_BYTES;
    /** Downloads stop while the project's disk has less than this free. */
    private static final long MIN_FREE_BYTES = 512L << 20;
    private static final Duration RETRY_AFTER = Duration.ofSeconds(60);
    private static final long SWEEP_SECONDS = 15;
    private static final Pattern WINDOWS_RESERVED = Pattern.compile("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?");

    /** {@code DWB_TRUST} value selecting the original protocol (open, or {@code DWB_SECRET}). */
    public static final String TRUST_LEGACY = "legacy";
    /** {@code DWB_TRUST} value (and default) selecting zero-trust mode. */
    public static final String TRUST_ZERO = "zero";

    /**
     * Network settings of this node.
     *
     * @param seeds unicast discovery endpoints for networks that block multicast and broadcast
     * @param trust zero-trust context (device key, trusted peers, pairing); {@code null} for the legacy protocol, where
     *              {@code security} applies. With it, the node id is always the device fingerprint.
     */
    public record Settings(String nodeId, String nodeName, InetAddress group, int discoveryPort, int transferPort,
                           Duration announceInterval, Duration peerTtl, List<InetSocketAddress> seeds,
                           LanSecurity security, long maxFileBytes, ZeroTrust trust) {
        public Settings {
            Objects.requireNonNull(group, "group must not be null");
            if (trust != null) {
                nodeId = trust.identity().fingerprint();
                trust.withLocalName(nodeName);
            }
            Objects.requireNonNull(nodeId, "nodeId must not be null");
            announceInterval = announceInterval == null ? Duration.ofSeconds(5) : announceInterval;
            peerTtl = peerTtl == null ? Duration.ofSeconds(20) : peerTtl;
            seeds = seeds == null ? List.of() : List.copyOf(seeds);
            security = security == null ? LanSecurity.open() : security;
            maxFileBytes = maxFileBytes <= 0 ? DEFAULT_MAX_FILE_BYTES : maxFileBytes;
        }

        /** Legacy-protocol settings (open, or authenticated by {@code security}). */
        public Settings(String nodeId, String nodeName, InetAddress group, int discoveryPort, int transferPort,
                        Duration announceInterval, Duration peerTtl, List<InetSocketAddress> seeds,
                        LanSecurity security, long maxFileBytes) {
            this(nodeId, nodeName, group, discoveryPort, transferPort, announceInterval, peerTtl, seeds, security,
                    maxFileBytes, null);
        }

        /** Whether peers are authenticated: zero-trust mode, or the legacy protocol with {@code DWB_SECRET}. */
        public boolean authenticated() {
            return trust != null || security.enabled();
        }

        /** {@code zero-trust}, {@code authenticated (DWB_SECRET)} or {@code OPEN mode}. */
        public String mode() {
            return trust != null ? "zero-trust" : security.enabled() ? "authenticated (DWB_SECRET)" : "OPEN mode";
        }

        /**
         * Settings from the environment (after the central policy's overrides). {@code DWB_TRUST} selects the mode:
         * {@code zero} (default) keeps a sealed device key and the trusted-peers list in {@code home}
         * ({@link ZeroTrust#load}); {@code legacy} uses the original protocol, where {@code DWB_SECRET} authenticates
         * peers and the node id is kept in {@code <home>/node.id}. {@code DWB_NAME} names the node (host name
         * otherwise), {@code DWB_LAN_SEEDS} lists {@code host[:port]} seeds separated by commas.
         */
        public static Settings fromEnvironment(Path home, UnaryOperator<String> env) throws IOException {
            List<InetSocketAddress> seeds = new ArrayList<>();
            String rawSeeds = env.apply("DWB_LAN_SEEDS");
            if (rawSeeds != null) {
                for (String token : rawSeeds.split("[,;\\s]+")) {
                    if (!token.isBlank()) {
                        seeds.add(seed(token.strip()));
                    }
                }
            }
            InetAddress group;
            try {
                group = InetAddress.getByName(PeerDiscovery.DEFAULT_GROUP);
            } catch (UnknownHostException e) {
                throw new IOException(e);
            }
            if (legacyTrust(env)) {
                return new Settings(nodeId(home), nodeName(env), group, PeerDiscovery.DEFAULT_PORT,
                        FileTransferService.DEFAULT_PORT, null, null, seeds,
                        LanSecurity.fromSecret(env.apply("DWB_SECRET")), DEFAULT_MAX_FILE_BYTES);
            }
            Files.createDirectories(home);
            ZeroTrust trust = ZeroTrust.load(home, MachineKey.detect());
            return new Settings(trust.identity().fingerprint(), nodeName(env), group, PeerDiscovery.DEFAULT_PORT,
                    FileTransferService.DEFAULT_PORT, null, null, seeds, LanSecurity.open(), DEFAULT_MAX_FILE_BYTES,
                    trust);
        }

        /** {@code DWB_TRUST=legacy}; unset or {@code zero} means zero-trust, anything else is an error. */
        public static boolean legacyTrust(UnaryOperator<String> env) {
            String mode = env.apply("DWB_TRUST");
            if (mode == null || mode.isBlank() || mode.strip().equalsIgnoreCase(TRUST_ZERO)) {
                return false;
            }
            if (mode.strip().equalsIgnoreCase(TRUST_LEGACY)) {
                return true;
            }
            throw new IllegalArgumentException("DWB_TRUST must be '" + TRUST_ZERO + "' (default) or '" + TRUST_LEGACY
                    + "', not '" + mode.strip() + "'");
        }

        /** One {@code DWB_LAN_SEEDS} entry: {@code host}, {@code host:port}, {@code [v6]} or {@code [v6]:port}. */
        public static InetSocketAddress seed(String token) {
            String host = token;
            String port = null;
            int colon = token.lastIndexOf(':');
            if (token.startsWith("[")) {
                int end = token.indexOf(']');
                if (end < 0) {
                    throw new IllegalArgumentException("DWB_LAN_SEEDS: unclosed [ in " + token);
                }
                host = token.substring(1, end);
                port = end + 1 < token.length() && token.charAt(end + 1) == ':' ? token.substring(end + 2) : null;
            } else if (colon > 0 && token.indexOf(':') == colon) {
                host = token.substring(0, colon);
                port = token.substring(colon + 1);
            }
            int number = PeerDiscovery.DEFAULT_PORT;
            if (port != null) {
                try {
                    number = Integer.parseInt(port);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("DWB_LAN_SEEDS: bad port in " + token);
                }
            }
            if (number < 1 || number > 65_535) {
                throw new IllegalArgumentException("DWB_LAN_SEEDS: port out of range in " + token);
            }
            return new InetSocketAddress(host, number);
        }

        private static String nodeId(Path home) throws IOException {
            Files.createDirectories(home);
            Path file = home.resolve("node.id");
            if (Files.isRegularFile(file)) {
                String id = Files.readString(file, StandardCharsets.US_ASCII).strip();
                if (id.matches("[0-9a-f]{16}")) {
                    return id;
                }
            }
            String id = HexFormat.of().formatHex(LanSecurity.nonce(8));
            Files.writeString(file, id, StandardCharsets.US_ASCII);
            return id;
        }

        private static String nodeName(UnaryOperator<String> env) {
            for (String key : new String[]{"DWB_NAME", "COMPUTERNAME", "HOSTNAME"}) {
                String v = env.apply(key);
                if (v != null && !v.isBlank()) {
                    return v.strip();
                }
            }
            try {
                return InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
                return "node";
            }
        }
    }

    /** Callbacks from the network side (background threads; keep them short). */
    public interface Listener {
        default void peerJoined(PeerInfo peer) {
        }

        default void peerLeft(PeerInfo peer) {
        }

        /** A burst of fetches finished: {@code names} were received and indexed. */
        default void received(List<String> names) {
        }

        /** A document could not be fetched or indexed; it is retried later unless it was unusable. */
        default void failed(String what, String reason) {
        }

        /**
         * A download of {@code what} (short content id) from {@code peer} of at least {@link #PROGRESS_MIN_BYTES} is
         * under way (its first bytes arrived). Called on the transfer thread: record or hand off, never block.
         */
        default void transferStarted(String what, String peer) {
        }

        /**
         * Download progress, at most once per 10 % and only for files of at least {@link #PROGRESS_MIN_BYTES}. Called
         * on the transfer thread: record or hand off, never block.
         */
        default void transferProgress(String what, String peer, long transferred, long total) {
        }

        /**
         * Live progress of a download that {@link #transferStarted started}, for a persistent indicator: at most once
         * per {@link #PROGRESS_TICK_NANOS} plus the final block ({@code transferred == total}, verification follows).
         * Called on the transfer thread: record or hand off, never block.
         */
        default void transferTick(String what, String peer, long transferred, long total) {
        }

        /**
         * A download that {@link #transferStarted started} is over: {@code verified} when its SHA-256 matched and it
         * was handed to the inbox (the outcome of indexing arrives through {@link #received}/{@link #failed}), false
         * when it broke off or was discarded. Called exactly once per started download.
         */
        default void transferFinished(String what, String peer, boolean verified) {
        }
    }

    /** Smaller downloads finish too quickly for progress reports to mean anything. */
    public static final long PROGRESS_MIN_BYTES = 64L << 20;

    /** Minimum spacing of {@link Listener#transferTick} calls. */
    public static final long PROGRESS_TICK_NANOS = 500_000_000L;

    /**
     * Turns per-block transfer callbacks into one start, at most nine {@link Listener#transferProgress} calls, throttled
     * {@link Listener#transferTick} calls and one {@link Listener#transferFinished}.
     */
    private static final class ProgressReporter implements FileTransferService.Progress {
        private final Listener listener;
        private final String what;
        private final String peer;
        private int lastDecile;
        private boolean started;
        private long lastTick;

        ProgressReporter(Listener listener, String what, String peer) {
            this.listener = listener;
            this.what = what;
            this.peer = peer;
        }

        @Override
        public void update(long transferred, long total) {
            if (total < PROGRESS_MIN_BYTES) {
                return;
            }
            int decile = (int) (transferred * 10 / total);
            try {
                if (!started) {
                    started = true;
                    lastDecile = decile; // a resumed download starts reporting from where it is
                    listener.transferStarted(what, peer);
                } else if (decile > lastDecile && decile < 10) {
                    lastDecile = decile;
                    listener.transferProgress(what, peer, transferred, total);
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "Progress listener failed: {0}", e.getMessage());
            }
            long now = System.nanoTime();
            if (transferred >= total || lastTick == 0 || now - lastTick >= PROGRESS_TICK_NANOS) {
                lastTick = now == 0 ? 1 : now;
                try {
                    listener.transferTick(what, peer, transferred, total);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.DEBUG, "Progress listener failed: {0}", e.getMessage());
                }
            }
        }

        /** Ends a started download's reports; a no-op when nothing was reported (small or already present). */
        void finish(boolean verified) {
            if (!started) {
                return;
            }
            started = false;
            try {
                listener.transferFinished(what, peer, verified);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "Progress listener failed: {0}", e.getMessage());
            }
        }
    }

    /** Snapshot for the {@code lan} terminal command. */
    public record Status(String nodeId, String nodeName, int transferPort, List<String> interfaces,
                         boolean authenticated, List<PeerInfo> peers, int shared, int received, int pending,
                         int declined, String mode) {
    }

    private final Settings settings;
    private final WorkbenchController controller;
    private final Path inbox;
    private final Listener listener;
    private final ContentStore store;
    private final Path declinedFile;
    /** Content the user removed from this index: never fetched again. */
    private final Set<String> declined = ConcurrentHashMap.newKeySet();
    /** Content that arrived but could not be indexed (unsupported, scanned, encrypted): not fetched again this run. */
    private final Set<String> unusable = ConcurrentHashMap.newKeySet();
    private final Set<String> queued = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> failedAt = new ConcurrentHashMap<>();
    private final List<String> burst = new ArrayList<>();
    private final AtomicInteger receivedTotal = new AtomicInteger();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("dwb-lan-sync").factory());
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("dwb-lan-sweep").daemon(true).factory());

    private FileTransferService transfer;
    private PeerDiscovery discovery;
    private Subscription events;
    private volatile boolean running;
    private boolean closed;

    public LanSyncService(Settings settings, WorkbenchController controller, Path projectRoot, Listener listener)
            throws IOException {
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.controller = Objects.requireNonNull(controller, "controller must not be null");
        Path root = projectRoot.toAbsolutePath().normalize();
        this.inbox = root.resolve(INBOX_DIR);
        this.listener = listener == null ? new Listener() { } : listener;
        // The controller registers binary assets in the project's store; LAN sync must serve from that very instance.
        ContentStore projectStore = controller.contentStore();
        this.store = projectStore.root().equals(root.resolve(STORE_DIR)) ? projectStore
                : new ContentStore(root.resolve(STORE_DIR));
        this.declinedFile = store.root().resolve(DECLINED_FILE);
    }

    /** Opens the sockets and announces the project's documents. Blocking (binds, stats files): not on the FX thread. */
    public synchronized void start() throws IOException {
        if (running || closed) {
            return;
        }
        loadDeclined();
        for (DocumentRecord d : controller.workbench().documents()) {
            share(d);
        }
        events = controller.onEvent(this::onEvent);
        FileTransferService t = transferService(settings.transferPort());
        try {
            t.start();
        } catch (BindException e) {
            if (settings.transferPort() == 0) {
                events.close();
                throw e;
            }
            // Another node (a dwb-cli on this machine) holds the default port; the announcement carries the real one.
            t = transferService(0);
            t.start();
        }
        PeerDiscovery.Config config = new PeerDiscovery.Config(settings.nodeId(), settings.nodeName(),
                settings.group(), settings.discoveryPort(), t.port(), settings.announceInterval(), settings.peerTtl(),
                settings.seeds());
        FileTransferService transferRef = t;
        PeerDiscovery.Listener discoveryListener = new PeerDiscovery.Listener() {
            @Override
            public void peerJoined(PeerInfo peer) {
                listener.peerJoined(peer);
                schedule(peer);
            }

            @Override
            public void peerLeft(PeerInfo peer) {
                listener.peerLeft(peer);
            }

            @Override
            public void catalogUpdated(PeerInfo peer) {
                schedule(peer);
            }
        };
        PeerDiscovery d = settings.trust() == null
                ? new PeerDiscovery(config, settings.security(), store::sharedHashes, discoveryListener)
                : new PeerDiscovery(config, settings.trust(),
                        peer -> transferRef.catalog(peer.transferEndpoint(), peer.nodeId()), store::sharedHashes,
                        discoveryListener);
        try {
            d.start();
        } catch (IOException | RuntimeException e) {
            t.close();
            events.close();
            throw e;
        }
        transfer = t;
        discovery = d;
        running = true;
        LanConsole.listen("LAN sync '" + settings.nodeName() + "' ready · transfer 0.0.0.0:" + t.port() + "/tcp · discovery "
                + settings.group().getHostAddress() + ":" + settings.discoveryPort() + "/udp"
                + (settings.seeds().isEmpty() ? "" : " + seeds " + settings.seeds()) + " · inbox " + inbox
                + " · " + settings.mode() + (settings.trust() == null ? ""
                : " (device " + DeviceIdentity.display(settings.nodeId()) + ", "
                + settings.trust().trust().devices().size() + " trusted)"));
        if (settings.trust() != null) {
            settings.trust().identity().notice().ifPresent(LanConsole::failed);
        }
        // Objects left by an interrupted earlier run (fetched, not yet delivered) are delivered first.
        for (StoredFile leftover : store.objects()) {
            if (wanted(leftover.sha256()) && queued.add(leftover.sha256())) {
                submit(() -> {
                    try {
                        deliver(leftover, "lan");
                    } finally {
                        finished(leftover.sha256());
                    }
                });
            }
        }
        sweeper.scheduleWithFixedDelay(this::sweep, SWEEP_SECONDS, SWEEP_SECONDS, TimeUnit.SECONDS);
    }

    private FileTransferService transferService(int port) {
        return settings.trust() == null
                ? new FileTransferService(store, settings.security(), port, settings.maxFileBytes(), this::onPushed)
                : new FileTransferService(store, settings.trust(), port, settings.maxFileBytes(), this::onPushed);
    }

    public boolean running() {
        return running;
    }

    /** Re-checks every known peer's catalog now (the {@code lan sync} command; also runs every 15 s). */
    public void syncNow() {
        failedAt.clear();
        PeerDiscovery d = discovery;
        if (d != null) {
            d.refreshCatalogs();
        }
        sweep();
    }

    // ================================================================== zero trust

    /** The zero-trust context, empty in legacy mode. */
    public Optional<ZeroTrust> zeroTrust() {
        return Optional.ofNullable(settings.trust());
    }

    /**
     * Pairs with the device at {@code endpoint}, which must show an open pairing PIN. Blocking (network): not on the
     * FX thread.
     */
    public FileTransferService.PairingResult pair(InetSocketAddress endpoint, String pin) throws IOException {
        FileTransferService t = transfer;
        if (t == null || settings.trust() == null) {
            throw new IllegalStateException("pairing needs LAN sync running in zero-trust mode");
        }
        FileTransferService.PairingResult result = t.pair(endpoint, pin, settings.nodeName());
        announce();
        return result;
    }

    /** A discovered peer by name, fingerprint (prefix) or a {@code host:port} endpoint. */
    public Optional<InetSocketAddress> endpoint(String token) {
        PeerDiscovery d = discovery;
        if (d != null) {
            Optional<PeerInfo> peer = d.find(token);
            if (peer.isPresent()) {
                return Optional.of(peer.get().transferEndpoint());
            }
        }
        int colon = token.lastIndexOf(':');
        if (colon > 0) {
            try {
                return Optional.of(new InetSocketAddress(token.substring(0, colon).replace("[", "").replace("]", ""),
                        Integer.parseInt(token.substring(colon + 1))));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Tells the peers that the catalog or an access list changed (they fetch their filtered catalog again). */
    public void catalogChanged() {
        announce();
    }

    public Status status() {
        PeerDiscovery d = discovery;
        FileTransferService t = transfer;
        return new Status(d == null ? settings.nodeId() : d.nodeId(), settings.nodeName(), t == null ? 0 : t.port(),
                d == null ? List.of() : d.interfaces(), settings.authenticated(),
                d == null ? List.of() : d.peers(), store.sharedHashes().size(), receivedTotal.get(), queued.size(),
                declined.size(), settings.mode());
    }

    public Path inbox() {
        return inbox;
    }

    public ContentStore store() {
        return store;
    }

    // ================================================================== local side

    private void onEvent(WorkbenchEvent event) {
        switch (event) {
            case WorkbenchEvent.Ingested(DocumentRecord d, long millis) -> {
                if (share(d)) {
                    if (declined.remove(d.sha256())) {
                        saveDeclined(); // the user added it again by hand
                    }
                    announce();
                }
            }
            case WorkbenchEvent.Removed(DocumentRecord d, boolean fromDisk) -> {
                store.unregister(d.sha256());
                if (!d.isSystemDocument() && declined.add(d.sha256())) {
                    saveDeclined();
                }
                announce();
            }
            case WorkbenchEvent.AssetRegistered a -> {
                if (declined.remove(a.asset().sha256())) {
                    saveDeclined(); // the user added it again by hand
                }
                announce(); // already registered in the shared store by the controller
            }
            default -> {
            }
        }
    }

    /** Offers a local document to peers, as long as its file still has the size that was indexed. */
    private boolean share(DocumentRecord d) {
        if (!d.local() || d.isSystemDocument()) {
            return false;
        }
        try {
            BasicFileAttributes attrs = Files.readAttributes(d.source(), BasicFileAttributes.class);
            long indexedModified = controller.sourceModifiedMillis(d.sha256());
            if (!attrs.isRegularFile() || attrs.size() != d.sizeBytes()
                    || (indexedModified > 0 && attrs.lastModifiedTime().toMillis() != indexedModified)) {
                return false; // changed since indexing: its bytes no longer match the hash (the watcher re-indexes it)
            }
            store.register(d.sha256(), d.source(), d.fileName());
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private void announce() {
        PeerDiscovery d = discovery;
        if (d != null) {
            d.catalogChanged();
        }
    }

    // ================================================================== remote side

    private boolean wanted(String sha256) {
        if (!Hashing.isSha256Hex(sha256) || declined.contains(sha256) || unusable.contains(sha256)
                || controller.document(sha256).isPresent()
                || store.registeredKind(sha256).orElse(null) == ContentKind.BINARY) {
            return false; // indexed here already, or a binary asset this node already holds
        }
        Long failed = failedAt.get(sha256);
        return failed == null || System.currentTimeMillis() - failed > RETRY_AFTER.toMillis();
    }

    private void schedule(PeerInfo peer) {
        if (!running || guest(peer)) {
            return;
        }
        for (String sha : peer.hashes()) {
            if (wanted(sha) && queued.add(sha)) {
                submit(() -> fetch(sha));
            }
        }
    }

    /**
     * Zero-trust mode: a {@link DeviceRole#RESTRICTED_GUEST} is never a source for background sync, or a guest could
     * slip documents into this index by merely sharing them (it cannot push them either). Explicit transfers stay
     * possible.
     */
    private boolean guest(PeerInfo peer) {
        ZeroTrust trust = settings.trust();
        return trust != null && trust.trust().find(peer.nodeId()).map(TrustStore.Device::guest).orElse(true);
    }

    private void sweep() {
        PeerDiscovery d = discovery;
        if (!running || d == null) {
            return;
        }
        try {
            d.peers().forEach(this::schedule);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "LAN sweep failed", e);
        }
    }

    private void submit(Runnable task) {
        try {
            worker.execute(task);
        } catch (RejectedExecutionException e) {
            // closing
        }
    }

    private void fetch(String sha256) {
        try {
            if (!running || !wanted(sha256)) {
                return;
            }
            long free = Files.getFileStore(store.root()).getUsableSpace();
            if (free < MIN_FREE_BYTES) {
                LanConsole.failed(sha256.substring(0, 12) + ": not enough free disk space in " + store.root());
                fail(sha256, sha256.substring(0, 12), "diskte yeterli boş alan yok");
                return;
            }
            String problem = "hiçbir eş bu içeriği artık sunmuyor";
            for (PeerInfo peer : discovery.holders(sha256).stream().filter(p -> !guest(p)).toList()) {
                if (!running) {
                    return;
                }
                InetSocketAddress endpoint = peer.transferEndpoint();
                LanConsole.start("Fetching " + sha256.substring(0, 12) + " from " + peer.name() + " ("
                        + endpoint.getAddress().getHostAddress() + ":" + endpoint.getPort() + ")");
                ProgressReporter progress = new ProgressReporter(listener, sha256.substring(0, 12), peer.name());
                boolean verified = false;
                try {
                    TransferResult result = transfer.pull(endpoint, peer.nodeId(), sha256, progress);
                    verified = true;
                    deliver(result.file(), peer.name());
                    return;
                } catch (IOException e) {
                    problem = peer.name() + ": " + e.getMessage();
                    LanConsole.failed(sha256.substring(0, 12) + " from " + peer.name() + " ("
                            + endpoint.getAddress().getHostAddress() + ":" + endpoint.getPort() + "): "
                            + LanConsole.reason(e));
                } finally {
                    progress.finish(verified);
                }
            }
            fail(sha256, sha256.substring(0, 12), problem);
        } catch (IOException | RuntimeException e) {
            LanConsole.failed(sha256.substring(0, 12) + ": " + LanConsole.reason(e));
            fail(sha256, sha256.substring(0, 12), e.getMessage());
        } finally {
            finished(sha256);
        }
    }

    /** A dwb-cli peer pushed a document ({@code push}): deliver it like a fetched one. */
    private void onPushed(StoredFile file, InetAddress from) {
        if (!running || !wanted(file.sha256()) || !queued.add(file.sha256())) {
            return;
        }
        String peer = discovery.peers().stream().filter(p -> p.address().equals(from)).map(PeerInfo::name)
                .findFirst().orElse(from.getHostAddress());
        submit(() -> {
            try {
                deliver(file, peer);
            } finally {
                finished(file.sha256());
            }
        });
    }

    /**
     * Moves verified content from the store into the project's inbox under its original (sanitized) name and hands
     * that path to the project's ingest pipeline. Runs on the sync worker only.
     */
    private void deliver(StoredFile file, String peerName) {
        String sha = file.sha256();
        Path target;
        try {
            if (file.path().startsWith(store.root())) {
                Path dir = inbox.resolve(safeName(peerName, "peer"));
                Files.createDirectories(dir);
                target = uniqueTarget(dir, safeName(file.name(), sha.substring(0, 12)));
                try {
                    Files.move(file.path(), target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(file.path(), target);
                }
                Files.deleteIfExists(file.path().resolveSibling(sha + ".name"));
            } else {
                target = file.path(); // already a registered local file
            }
        } catch (IOException | RuntimeException e) {
            LanConsole.failed(file.name() + " from " + peerName + " could not be written to " + INBOX_DIR + ": "
                    + LanConsole.reason(e));
            fail(sha, file.name(), "gelen kutusuna yazılamadı: " + e.getMessage());
            return;
        }
        // Same decision as a dwb-cli node: a blob whose name says nothing (no or unknown extension) is recognized by
        // its bytes and kept as a binary asset instead of being discarded as an unsupported document.
        if (!controller.workbench().supports(target)
                && DocumentParser.classify(target, file.name()) != ContentKind.BINARY) {
            LanConsole.failed(file.name() + " from " + peerName + ": unsupported file type, discarded");
            unusable.add(sha);
            deleteQuietly(target);
            listener.failed(file.name(), "desteklenmeyen dosya türü");
            return;
        }
        try {
            IngestResult result = controller.ingest(target);
            if (result instanceof IngestResult.Ingested(DocumentRecord d, long millis)) {
                receivedTotal.incrementAndGet();
                synchronized (burst) {
                    burst.add(d.fileName());
                }
                LOG.log(System.Logger.Level.INFO, "Received {0} from {1}", d.fileName(), peerName);
                LanConsole.success("Saved to " + displayPath(target));
            } else if (result instanceof IngestResult.Registered(BinaryAsset a, long millis)) {
                // A binary asset stays in the inbox as it is: verified by the transfer, never parsed or indexed.
                receivedTotal.incrementAndGet();
                synchronized (burst) {
                    burst.add(a.fileName());
                }
                LOG.log(System.Logger.Level.INFO, "Received binary asset {0} from {1}", a.fileName(), peerName);
                LanConsole.success("Saved to " + displayPath(target) + " (binary asset, not indexed)");
            } else {
                deleteQuietly(target); // indexed meanwhile under another path: no second copy
                LanConsole.success(file.name() + " from " + peerName + " is already indexed here; no second copy kept");
            }
        } catch (IngestionException e) {
            LanConsole.failed(file.name() + " from " + peerName + " could not be indexed: " + e.getMessage());
            deleteQuietly(target);
            if (e.reason() == IngestionException.Reason.MEMORY_PRESSURE) {
                fail(sha, file.name(), e.getMessage()); // transient: retried later
            } else {
                unusable.add(sha);
                listener.failed(file.name(), e.getMessage());
            }
        }
    }

    private void fail(String sha256, String what, String reason) {
        failedAt.put(sha256, System.currentTimeMillis());
        LOG.log(System.Logger.Level.DEBUG, "LAN fetch of {0} failed: {1}", what, reason);
        listener.failed(what, reason);
    }

    /** Reports a burst once the queue drains, and persists the index once for it. */
    private void finished(String sha256) {
        queued.remove(sha256);
        if (!queued.isEmpty()) {
            return;
        }
        List<String> names;
        synchronized (burst) {
            if (burst.isEmpty()) {
                return;
            }
            names = List.copyOf(burst);
            burst.clear();
        }
        controller.saveAsync();
        listener.received(names);
    }

    // ================================================================== files

    /** {@code lan-inbox/<peer>/<name>} for the console: relative to the project, with forward slashes. */
    private String displayPath(Path target) {
        Path project = inbox.getParent();
        Path shown = project != null && target.startsWith(project) ? project.relativize(target) : target;
        return shown.toString().replace('\\', '/');
    }

    /** A single path segment that is valid on Windows and Linux alike; the fallback when nothing usable is left. */
    public static String safeName(String raw, String fallback) {
        String clean = ContentStore.sanitizeName(raw).replace('/', '_').replace('\\', '_');
        while (!clean.isEmpty() && (clean.endsWith(".") || clean.endsWith(" "))) {
            clean = clean.substring(0, clean.length() - 1); // Windows drops trailing dots and blanks
        }
        if (clean.isEmpty() || clean.equals("document") && !raw.strip().equalsIgnoreCase("document")) {
            clean = fallback;
        }
        if (WINDOWS_RESERVED.matcher(clean).matches()) {
            clean = "_" + clean; // reserved names are short: still far below the byte limit
        }
        return clean;
    }

    /** {@code name.ext}, else {@code name (2).ext}, {@code name (3).ext}, … */
    private static Path uniqueTarget(Path dir, String name) {
        Path candidate = dir.resolve(name);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; Files.exists(candidate); i++) {
            // The suffix must not push a name that already uses the whole byte budget over the file system limit.
            String suffix = " (" + i + ")" + ext;
            candidate = dir.resolve(ContentStore.fitBytes(stem, ContentStore.MAX_NAME_BYTES
                    - suffix.getBytes(StandardCharsets.UTF_8).length) + suffix);
        }
        return candidate;
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // left behind in the inbox; harmless
        }
    }

    private void loadDeclined() throws IOException {
        declined.clear();
        if (Files.isRegularFile(declinedFile)) {
            for (String line : Files.readAllLines(declinedFile, StandardCharsets.UTF_8)) {
                String h = line.strip().toLowerCase(Locale.ROOT);
                if (Hashing.isSha256Hex(h)) {
                    declined.add(h);
                }
            }
        }
    }

    private synchronized void saveDeclined() {
        try {
            Path temp = declinedFile.resolveSibling(DECLINED_FILE + ".tmp");
            Files.write(temp, new TreeSet<>(declined), StandardCharsets.UTF_8);
            try {
                Files.move(temp, declinedFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, declinedFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot save {0}: {1}", declinedFile, e.getMessage());
        }
    }

    // ================================================================== lifecycle

    /** Says goodbye to the peers, closes the sockets and waits briefly for a running delivery to finish. */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            running = false;
        }
        sweeper.shutdownNow();
        if (events != null) {
            events.close();
        }
        if (discovery != null) {
            discovery.close();
        }
        if (transfer != null) {
            transfer.close();
        }
        worker.shutdownNow();
        try {
            worker.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
