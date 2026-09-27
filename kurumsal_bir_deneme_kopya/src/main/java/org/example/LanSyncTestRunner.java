package org.example;

import org.example.core.Workbench;
import org.example.index.InvertedIndex;
import org.example.ingest.DocumentParser;
import org.example.model.DocumentRecord;
import org.example.p2p.ContentStore;
import org.example.p2p.FileTransferService;
import org.example.p2p.LanSecurity;
import org.example.p2p.LanSyncService;
import org.example.p2p.PeerDiscovery;
import org.example.p2p.PeerInfo;
import org.example.platform.OsShellBridge;
import org.example.repl.InternalTerminalEngine;
import org.example.state.ViewModeCoordinator.MemorySnapshot;
import org.example.util.Hashing;
import org.example.workbench.WorkbenchController;
import org.example.workbench.WorkbenchController.RemovalResult;
import org.example.workbench.WorkbenchController.WorkbenchEvent;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * Headless verification of LAN sync and batch removal, run under the 512 MB heap ceiling:
 *
 * <ol>
 *   <li>Discovery datagram parsing on loopback — hand-crafted {@code ANN}/{@code HASH} packets, malformed and
 *       forged ones, authenticated mode, two real nodes seeded to each other.</li>
 *   <li>End-to-end sync between three nodes (each its own project, controller and {@link LanSyncService}): SHA-256
 *       verified transfer, write into {@code lan-inbox}, ingestion into the inverted index, de-duplication, decline
 *       after removal, and a transfer whose bytes do not match their hash.</li>
 *   <li>Inverted-index eviction for single and batch removal ({@link WorkbenchController#untrackAll}), persistence
 *       of the removal, and removal racing concurrent searches.</li>
 * </ol>
 *
 * <p>Every socket is bound on this machine and every peer is reached through {@code 127.0.0.1} seeds on random
 * ports, so the runner neither needs nor disturbs a real LAN node.</p>
 *
 * <pre>gradle -q runLanTests</pre>
 */
public final class LanSyncTestRunner {

    private static final long HEAP_BUDGET = 200L << 20;
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    private int passed;
    private final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        LanSyncTestRunner runner = new LanSyncTestRunner();
        Path root = Files.createTempDirectory("dwb-lan-");
        try {
            runner.discoveryParsing();
            runner.endToEnd(root.resolve("e2e"));
            runner.integrity(root.resolve("integrity"));
            runner.indexEviction(root.resolve("evict"));
            runner.section("memory");
            runner.check("peak heap stayed below 200 MB", peakHeapBytes() < HEAP_BUDGET,
                    (peakHeapBytes() >> 20) + " MB");
        } finally {
            deleteRecursively(root);
        }
        System.out.println();
        System.out.printf("%d passed, %d failed%n", runner.passed, runner.failures.size());
        runner.failures.forEach(f -> System.out.println("  FAILED: " + f));
        System.out.println("jvm: " + MemorySnapshot.now().describe() + ", peak heap " + (peakHeapBytes() >> 20) + " MB");
        System.exit(runner.failures.isEmpty() ? 0 : 1);
    }

    // ================================================================== 1. discovery parsing

    private void discoveryParsing() throws Exception {
        section("peer discovery: datagram parsing on loopback");
        Map<String, PeerInfo> joined = new ConcurrentHashMap<>();
        Map<String, PeerInfo> catalogs = new ConcurrentHashMap<>();
        PeerDiscovery.Listener recorder = new PeerDiscovery.Listener() {
            @Override
            public void peerJoined(PeerInfo peer) {
                joined.put(peer.nodeId(), peer);
            }

            @Override
            public void catalogUpdated(PeerInfo peer) {
                catalogs.put(peer.nodeId(), peer);
            }
        };
        int port = freeUdpPort();
        try (PeerDiscovery node = discovery("aaaaaaaaaaaaaaaa", "Masaustu", port, 47778, List.of(),
                LanSecurity.open(), Set::of, recorder);
             DatagramSocket out = new DatagramSocket(0, LOOPBACK)) {
            node.start();
            InetSocketAddress to = new InetSocketAddress(LOOPBACK, port);

            send(out, to, datagram(LanSecurity.open(), "t=ANN", "id=0123456789abcdef", "name=Laptop Mint",
                    "tp=47778", "cv=00000000000000aa", "cnt=1", "ts=" + System.currentTimeMillis()));
            waitFor(() -> joined.containsKey("0123456789abcdef"));
            PeerInfo p = joined.get("0123456789abcdef");
            check("ANN datagram parsed into a peer", p != null, "no peer after a valid announcement");
            if (p != null) {
                check("ANN fields: name, transfer port, advertised count, source address",
                        p.name().equals("Laptop Mint") && p.transferPort() == 47778 && p.advertised() == 1
                                && p.address().isLoopbackAddress(), p.toString());
            }

            String hash = Hashing.hex(Hashing.sha256().digest("belge".getBytes(StandardCharsets.UTF_8)));
            send(out, to, datagram(LanSecurity.open(), "t=HASH", "id=0123456789abcdef", "cv=00000000000000aa",
                    "part=0", "parts=1", "ts=" + System.currentTimeMillis(), "h=" + hash + ",not-a-hash"));
            waitFor(() -> catalogs.containsKey("0123456789abcdef"));
            PeerInfo c = catalogs.get("0123456789abcdef");
            check("HASH datagram completes the peer's catalog (invalid hashes dropped)",
                    c != null && c.hashes().equals(Set.of(hash)) && c.catalogComplete(), String.valueOf(c));
            check("holders() finds the peer by content hash",
                    node.holders(hash).stream().anyMatch(h -> h.nodeId().equals("0123456789abcdef")), "");

            // Malformed and hostile datagrams: none may create a peer.
            send(out, to, "HELLO\nt=ANN\nid=1111111111111111\n".getBytes(StandardCharsets.UTF_8));
            send(out, to, datagram(LanSecurity.open(), "t=ANN", "id=NOT-HEX", "tp=1", "cv=00000000000000aa",
                    "cnt=0"));
            send(out, to, datagram(LanSecurity.open(), "t=ANN", "id=2222222222222222", "tp=70000",
                    "cv=00000000000000aa", "cnt=0"));
            send(out, to, datagram(LanSecurity.open(), "t=ANN", "id=3333333333333333", "tp=5000",
                    "cv=00000000000000aa", "cnt=-4"));
            send(out, to, datagram(LanSecurity.open(), "t=ANN", "id=aaaaaaaaaaaaaaaa", "tp=5000",
                    "cv=00000000000000aa", "cnt=0")); // our own id echoed back
            send(out, to, new byte[3_000]);
            Thread.sleep(400);
            check("malformed, out-of-range and self-echo datagrams are ignored",
                    node.peers().size() == 1, "peers: " + node.peers());
        }

        section("peer discovery: authenticated mode (DWB_SECRET)");
        LanSecurity secret = LanSecurity.fromSecret("ofis-2026");
        joined.clear();
        int securePort = freeUdpPort();
        try (PeerDiscovery node = discovery("bbbbbbbbbbbbbbbb", "Guvenli", securePort, 47778, List.of(), secret,
                Set::of, recorder);
             DatagramSocket out = new DatagramSocket(0, LOOPBACK)) {
            node.start();
            InetSocketAddress to = new InetSocketAddress(LOOPBACK, securePort);
            send(out, to, datagram(LanSecurity.fromSecret("yanlis"), "t=ANN", "id=4444444444444444", "name=Yabanci",
                    "tp=5000", "cv=00000000000000aa", "cnt=0", "ts=" + System.currentTimeMillis()));
            send(out, to, datagram(secret, "t=ANN", "id=5555555555555555", "name=Eski", "tp=5000",
                    "cv=00000000000000aa", "cnt=0", "ts=" + (System.currentTimeMillis() - 600_000)));
            send(out, to, datagram(secret, "t=ANN", "id=6666666666666666", "name=Dost", "tp=5000",
                    "cv=00000000000000aa", "cnt=0", "ts=" + System.currentTimeMillis()));
            waitFor(() -> joined.containsKey("6666666666666666"));
            check("a correctly signed announcement is accepted", joined.containsKey("6666666666666666"), "");
            check("wrong key and stale timestamps are rejected",
                    !joined.containsKey("4444444444444444") && !joined.containsKey("5555555555555555"),
                    joined.keySet().toString());
        }

        section("peer discovery: two nodes over loopback seeds");
        int portA = freeUdpPort();
        int portB = freeUdpPort();
        String hashA = Hashing.hex(Hashing.sha256().digest("A".getBytes(StandardCharsets.UTF_8)));
        Map<String, PeerInfo> seenByB = new ConcurrentHashMap<>();
        try (PeerDiscovery a = discovery("cccccccccccccccc", "Node-A", portA, 40001,
                List.of(new InetSocketAddress(LOOPBACK, portB)), LanSecurity.open(), () -> Set.of(hashA), null);
             PeerDiscovery b = discovery("dddddddddddddddd", "Node-B", portB, 40002,
                     List.of(new InetSocketAddress(LOOPBACK, portA)), LanSecurity.open(), Set::of,
                     new PeerDiscovery.Listener() {
                         @Override
                         public void catalogUpdated(PeerInfo peer) {
                             seenByB.put(peer.nodeId(), peer);
                         }
                     })) {
            a.start();
            b.start();
            waitFor(() -> a.find("Node-B").isPresent() && seenByB.containsKey("cccccccccccccccc"));
            check("both nodes discover each other", a.find("Node-B").isPresent() && b.find("Node-A").isPresent(), "");
            PeerInfo seen = seenByB.get("cccccccccccccccc");
            check("B learns A's transfer port and SHA-256 catalog",
                    seen != null && seen.transferPort() == 40001 && seen.hashes().contains(hashA), String.valueOf(seen));
        }

        section("peer discovery: two machines with the same node id (cloned image / copied workspace)");
        int clonePortA = freeUdpPort();
        int clonePortB = freeUdpPort();
        try (PeerDiscovery a = discovery("eeeeeeeeeeeeeeee", "Klon-A", clonePortA, 40003,
                List.of(new InetSocketAddress(LOOPBACK, clonePortB)), LanSecurity.open(), Set::of, null);
             PeerDiscovery b = discovery("eeeeeeeeeeeeeeee", "Klon-B", clonePortB, 40004,
                     List.of(new InetSocketAddress(LOOPBACK, clonePortA)), LanSecurity.open(), Set::of, null)) {
            a.start();
            b.start();
            waitFor(() -> a.find("Klon-B").isPresent() && b.find("Klon-A").isPresent());
            check("clones detect the id collision, re-key and see each other",
                    a.find("Klon-B").isPresent() && b.find("Klon-A").isPresent() && !a.nodeId().equals(b.nodeId()),
                    a.nodeId() + " / " + b.nodeId());
        }

        section("settings parsing");
        check("seed without port uses the discovery port",
                LanSyncService.Settings.seed("192.168.1.8").getPort() == PeerDiscovery.DEFAULT_PORT, "");
        check("seed host:port", LanSyncService.Settings.seed("192.168.1.6:5000").getPort() == 5000, "");
        check("seed [v6]:port and bare v6", LanSyncService.Settings.seed("[::1]:6000").getPort() == 6000
                && LanSyncService.Settings.seed("::1").getPort() == PeerDiscovery.DEFAULT_PORT, "");
        boolean rejected;
        try {
            LanSyncService.Settings.seed("host:99999");
            rejected = false;
        } catch (IllegalArgumentException e) {
            rejected = true;
        }
        check("seed with an out-of-range port is rejected", rejected, "");
        check("received names are valid on Windows and Linux",
                LanSyncService.safeName("CON.txt", "x").equals("_CON.txt")
                        && LanSyncService.safeName("..\\..\\etc/passwd", "x").equals("passwd")
                        && LanSyncService.safeName("rapor. ", "x").equals("rapor")
                        && LanSyncService.safeName("a:b*c?.pdf", "x").equals("abc.pdf")
                        && LanSyncService.safeName("", "fallback").equals("fallback"),
                LanSyncService.safeName("rapor. ", "x"));
    }

    // ================================================================== 2. end to end

    private record Node(String name, Path project, WorkbenchController controller, LanSyncService sync,
                        List<List<String>> received) implements AutoCloseable {
        @Override
        public void close() {
            sync.close();
            controller.close();
        }
    }

    private void endToEnd(Path root) throws Exception {
        section("LAN sync: three nodes, file ingested on one appears on the others");
        int[] ports = {freeUdpPort(), freeUdpPort(), freeUdpPort()};
        String[] names = {"Masaustu-A", "Laptop-B", "Laptop-C"};
        List<Node> nodes = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                List<InetSocketAddress> seeds = new ArrayList<>();
                for (int j = 0; j < 3; j++) {
                    if (j != i) {
                        seeds.add(new InetSocketAddress(LOOPBACK, ports[j]));
                    }
                }
                nodes.add(node(root.resolve(names[i]), names[i], ports[i], seeds));
            }
            Node a = nodes.get(0);
            Node b = nodes.get(1);
            Node c = nodes.get(2);
            waitFor(() -> nodes.stream().allMatch(n -> n.sync().status().peers().size() == 2));
            check("every node sees the two others", nodes.stream().allMatch(n -> n.sync().status().peers().size() == 2),
                    nodes.stream().map(n -> n.name() + "=" + n.sync().status().peers().size()).toList().toString());

            // --- A ingests through the UI's own pipeline (chassis ingest -> addPath)
            Path alpha = write(root.resolve("src-a").resolve("alfa rapor.txt"),
                    "Zebrafjord çeyrek raporu: satışlar yüzde on iki arttı.\n".repeat(40));
            String alphaSha = Hashing.sha256Hex(alpha);
            long started = System.nanoTime();
            a.controller().addPath(alpha, InternalTerminalEngine.Output.NONE);
            waitFor(() -> indexed(b, alphaSha) && indexed(c, alphaSha));
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            check("A's new document is indexed on B and C", indexed(b, alphaSha) && indexed(c, alphaSha),
                    "B=" + indexed(b, alphaSha) + " C=" + indexed(c, alphaSha));
            System.out.println("        (propagated in " + millis + " ms)");

            Path inboxCopy = b.project().resolve(LanSyncService.INBOX_DIR).resolve(a.name()).resolve("alfa rapor.txt");
            check("B wrote the bytes into <project>/lan-inbox/<peer>/<original name>", Files.isRegularFile(inboxCopy),
                    inboxCopy.toString());
            check("the written file's SHA-256 equals the source's",
                    Files.isRegularFile(inboxCopy) && Hashing.sha256Hex(inboxCopy).equals(alphaSha), "");
            DocumentRecord onB = b.controller().document(alphaSha).orElse(null);
            check("the received document is a normal local document pointing at the inbox copy",
                    onB != null && onB.local() && onB.source().toAbsolutePath().normalize().equals(
                            inboxCopy.toAbsolutePath().normalize()), String.valueOf(onB));
            check("the received document is searchable on B (inverted index)",
                    b.controller().search("zebrafjord", 5).hits().stream().anyMatch(h -> h.docId().equals(alphaSha)),
                    "");
            waitFor(() -> !b.received().isEmpty());
            check("B's listener got one burst notification naming the file",
                    b.received().stream().anyMatch(batch -> batch.contains("alfa rapor.txt")), b.received().toString());
            check("nothing bounced back: A still holds exactly one document",
                    a.controller().workbench().documents().size() == 1, "");

            // --- de-duplication
            Path alphaCopy = write(root.resolve("src-a").resolve("alfa kopya.txt"), Files.readString(alpha));
            a.controller().addPath(alphaCopy, InternalTerminalEngine.Output.NONE);
            b.sync().syncNow();
            Thread.sleep(1_500);
            try (Stream<Path> inbox = Files.list(inboxCopy.getParent())) {
                check("identical content under another name is not transferred again (SHA-256 dedup)",
                        inbox.count() == 1 && b.controller().workbench().documents().size() == 1, "");
            }

            // --- the other direction, and a multi-document burst
            Path gamma = write(root.resolve("src-c").resolve("gama.txt"), "Kestane menüsü ve fiyat listesi.\n".repeat(30));
            Path delta = write(root.resolve("src-c").resolve("delta.txt"), "Sunucu bakım takvimi, mart ayı.\n".repeat(30));
            c.controller().addPath(gamma.getParent(), InternalTerminalEngine.Output.NONE);
            String gammaSha = Hashing.sha256Hex(gamma);
            String deltaSha = Hashing.sha256Hex(delta);
            waitFor(() -> indexed(a, gammaSha) && indexed(a, deltaSha) && indexed(b, gammaSha) && indexed(b, deltaSha));
            check("a folder added on C reaches A and B (both files)",
                    indexed(a, gammaSha) && indexed(a, deltaSha) && indexed(b, gammaSha) && indexed(b, deltaSha), "");

            // --- decline: a removed document is not fetched again
            RemovalResult removed = b.controller().untrack(alphaSha);
            check("B removes the received document from its index", removed.ok(), removed.describe());
            b.sync().syncNow();
            Thread.sleep(2_000);
            check("the sync does not resurrect a document the user removed", !indexed(b, alphaSha), "");
            Path declined = b.project().resolve(LanSyncService.STORE_DIR).resolve("declined.txt");
            check("the decline survives restarts (lan/declined.txt)", Files.isRegularFile(declined)
                    && Files.readString(declined).contains(alphaSha), "");
            b.controller().addPath(inboxCopy, InternalTerminalEngine.Output.NONE);
            check("adding the file again by hand lifts the decline",
                    indexed(b, alphaSha) && !Files.readString(declined).contains(alphaSha), "");

            // --- shutdown releases the ports
            int tcp = c.sync().status().transferPort();
            c.close();
            nodes.remove(c);
            boolean free;
            try (ServerSocket probe = new ServerSocket()) {
                probe.setReuseAddress(true);
                probe.bind(new InetSocketAddress(tcp));
                free = true;
            } catch (IOException e) {
                free = false;
            }
            check("closing a node releases its TCP transfer port", free, "port " + tcp);
            waitFor(() -> a.sync().status().peers().size() == 1);
            check("peers notice the departure (BYE)", a.sync().status().peers().size() == 1, "");
        } finally {
            nodes.forEach(Node::close);
        }
    }

    private Node node(Path dir, String name, int discoveryPort, List<InetSocketAddress> seeds) throws IOException {
        Path project = Files.createDirectories(dir);
        WorkbenchController controller = new WorkbenchController(
                new Workbench(new DocumentParser(), new InvertedIndex(), null),
                WorkbenchController.Config.defaults(project), new OsShellBridge());
        LanSyncService.Settings settings = new LanSyncService.Settings(HexFormat.of().formatHex(LanSecurity.nonce(8)),
                name, InetAddress.getByName(PeerDiscovery.DEFAULT_GROUP), discoveryPort, 0, Duration.ofSeconds(1),
                Duration.ofSeconds(10), seeds, LanSecurity.open(), 0);
        List<List<String>> received = new CopyOnWriteArrayList<>();
        LanSyncService sync = new LanSyncService(settings, controller, project, new LanSyncService.Listener() {
            @Override
            public void received(List<String> names) {
                received.add(names);
            }
        });
        sync.start();
        return new Node(name, project, controller, sync, received);
    }

    private static boolean indexed(Node node, String sha256) {
        return node.controller().document(sha256).isPresent();
    }

    // ================================================================== 2b. integrity

    private void integrity(Path root) throws Exception {
        section("byte transfer: SHA-256 verification before anything is written");
        ContentStore serverStore = new ContentStore(root.resolve("server"));
        ContentStore clientStore = new ContentStore(root.resolve("client"));
        Path real = write(root.resolve("files").resolve("dogru.txt"), "gerçek içerik\n".repeat(100));
        Path forged = write(root.resolve("files").resolve("sahte.txt"), "sahte içerik!\n".repeat(100));
        String realSha = Hashing.sha256Hex(real);
        // A lying peer: serves other bytes under the advertised hash.
        serverStore.register(realSha, forged, "dogru.txt");
        AtomicReference<String> error = new AtomicReference<>("");
        try (FileTransferService server = new FileTransferService(serverStore, LanSecurity.open(), 0, 1 << 20, null);
             FileTransferService client = new FileTransferService(clientStore, LanSecurity.open(), 0, 1 << 20, null)) {
            server.start();
            try {
                client.pull(new InetSocketAddress(LOOPBACK, server.port()), realSha, FileTransferService.Progress.NONE);
            } catch (FileTransferService.TransferException e) {
                error.set(e.getMessage());
            }
            check("bytes that do not match their SHA-256 are rejected", error.get().contains("do not match"), error.get());
            check("nothing was committed and the partial file was discarded",
                    !clientStore.has(realSha) && !Files.exists(clientStore.partialPath(realSha)), "");

            serverStore.register(realSha, real, "dogru.txt");
            FileTransferService.TransferResult ok = client.pull(new InetSocketAddress(LOOPBACK, server.port()), realSha,
                    FileTransferService.Progress.NONE);
            check("the honest transfer verifies and commits", ok.outcome() == FileTransferService.Outcome.RECEIVED
                    && Hashing.sha256Hex(ok.file().path()).equals(realSha), ok.outcome().toString());
            FileTransferService.TransferResult again = client.pull(new InetSocketAddress(LOOPBACK, server.port()),
                    realSha, FileTransferService.Progress.NONE);
            check("content already present is not transferred twice",
                    again.outcome() == FileTransferService.Outcome.ALREADY_PRESENT && again.bytes() == 0, "");
        }
    }

    // ================================================================== 3. index eviction

    private void indexEviction(Path root) throws Exception {
        section("removal: inverted-index eviction, single and batch");
        Path data = Files.createDirectories(root.resolve("data"));
        Path docs = Files.createDirectories(root.resolve("docs"));
        Path kalem = write(docs.resolve("kalem.txt"), "kalemtıraş kayısı stok sayımı\n".repeat(20));
        Path defter = write(docs.resolve("defter.txt"), "defterlik kayısı sipariş formu\n".repeat(20));
        Path silgi = write(docs.resolve("silgi.txt"), "silgilik kayısı fatura listesi\n".repeat(20));
        Path cetvel = write(docs.resolve("cetvel.txt"), "cetvelcilik ölçüm tablosu\n".repeat(20));
        String kalemSha = Hashing.sha256Hex(kalem);
        String defterSha = Hashing.sha256Hex(defter);
        String silgiSha = Hashing.sha256Hex(silgi);
        String cetvelSha = Hashing.sha256Hex(cetvel);

        try (WorkbenchController c = new WorkbenchController(new Workbench(new DocumentParser(), new InvertedIndex(),
                null), WorkbenchController.Config.defaults(data), new OsShellBridge())) {
            c.addPath(docs, InternalTerminalEngine.Output.NONE);
            List<WorkbenchEvent.Removed> events = new CopyOnWriteArrayList<>();
            c.onEvent(e -> {
                if (e instanceof WorkbenchEvent.Removed r) {
                    events.add(r);
                }
            });
            long postingsBefore = c.workbench().stats().postings();
            check("fixture: four documents, 'kayısı' in three", c.workbench().documents().size() == 4
                    && hits(c, "kayısı") == 3, hits(c, "kayısı") + " hits");

            RemovalResult single = c.untrack(kalemSha);
            check("single removal succeeds", single instanceof RemovalResult.Untracked, single.describe());
            check("single removal evicts the document's unique token", hits(c, "kalemtıraş") == 0, "");
            check("shared tokens only lose the removed document's postings", hits(c, "kayısı") == 2, "");
            check("the index no longer contains the document", !c.workbench().index().contains(kalemSha), "");

            List<RemovalResult> batch = c.untrackAll(List.of(defterSha, silgiSha, defterSha));
            check("batch removal: one result per distinct document, all removed",
                    batch.size() == 2 && batch.stream().allMatch(RemovalResult::ok), batch.toString());
            check("batch removal evicts every selected document's tokens",
                    hits(c, "kayısı") == 0 && hits(c, "defterlik") == 0 && hits(c, "silgilik") == 0, "");
            check("the untouched document is still searchable", hits(c, "cetvelcilik") == 1, "");
            // Removal tombstones the chunks at once (postings leave the live count); the term dictionary itself is
            // rebuilt lazily by InvertedIndex.compact() once dead chunks outnumber live ones.
            check("live postings shrink with the evicted documents", c.workbench().stats().postings() < postingsBefore,
                    postingsBefore + " -> " + c.workbench().stats().postings());
            check("a Removed event per document (drives table rows and LAN decline)", events.size() == 3,
                    events.size() + " events");
            check("files on disk are untouched by index removal",
                    Files.exists(kalem) && Files.exists(defter) && Files.exists(silgi), "");

            List<RemovalResult> mixed = c.untrackAll(List.of(kalemSha, "f".repeat(64)));
            check("removing already-removed or unknown documents is rejected, not thrown",
                    mixed.stream().noneMatch(RemovalResult::ok), mixed.toString());
            c.save();
        }

        try (WorkbenchController reopened = new WorkbenchController(new Workbench(new DocumentParser(),
                new InvertedIndex(), null), WorkbenchController.Config.defaults(data), new OsShellBridge())) {
            reopened.startup();
            List<String> left = reopened.workbench().documents().stream().map(DocumentRecord::sha256).toList();
            check("removals persist: the reloaded snapshot holds only the kept document",
                    left.equals(List.of(cetvelSha)), left.toString());
        }

        section("removal: batch eviction racing concurrent searches");
        Path many = Files.createDirectories(root.resolve("many"));
        for (int i = 0; i < 40; i++) {
            write(many.resolve("not-" + i + ".txt"), ("ortak kelime belge" + i + " içerik satırı\n").repeat(10));
        }
        try (WorkbenchController c = new WorkbenchController(new Workbench(new DocumentParser(), new InvertedIndex(),
                null), WorkbenchController.Config.defaults(root.resolve("data2")), new OsShellBridge())) {
            c.addPath(many, InternalTerminalEngine.Output.NONE);
            AtomicBoolean stop = new AtomicBoolean();
            AtomicReference<Throwable> searchError = new AtomicReference<>();
            Thread searcher = Thread.ofVirtual().start(() -> {
                while (!stop.get()) {
                    try {
                        c.search("ortak kelime", 20);
                    } catch (Throwable t) {
                        searchError.compareAndSet(null, t);
                    }
                }
            });
            // A live view of the whole table, as Ctrl+A would hand it over.
            List<RemovalResult> results = c.untrackAll(c.workbench().documents().stream()
                    .map(DocumentRecord::sha256).toList());
            stop.set(true);
            searcher.join();
            check("removing all 40 documents in one batch succeeds", results.size() == 40
                    && results.stream().allMatch(RemovalResult::ok), "");
            check("no ConcurrentModificationException or other error in parallel searches",
                    searchError.get() == null, String.valueOf(searchError.get()));
            check("the index is empty afterwards", c.workbench().documents().isEmpty() && hits(c, "ortak") == 0, "");
        }
    }

    private static int hits(WorkbenchController c, String query) {
        return (int) c.search(query, 50).hits().stream().map(h -> h.docId()).distinct().count();
    }

    // ================================================================== harness

    private static PeerDiscovery discovery(String id, String name, int port, int transferPort,
                                           List<InetSocketAddress> seeds, LanSecurity security,
                                           java.util.function.Supplier<Set<String>> hashes,
                                           PeerDiscovery.Listener listener) throws IOException {
        return new PeerDiscovery(new PeerDiscovery.Config(id, name, InetAddress.getByName(PeerDiscovery.DEFAULT_GROUP),
                port, transferPort, Duration.ofSeconds(1), Duration.ofSeconds(10), seeds), security, hashes, listener);
    }

    /** A discovery datagram exactly as {@link PeerDiscovery} encodes it: header, fields, HMAC line. */
    private static byte[] datagram(LanSecurity security, String... fields) {
        StringBuilder sb = new StringBuilder("DWB1\n");
        for (String f : fields) {
            sb.append(f).append('\n');
        }
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("mac=" + Hashing.hex(security.mac(body)) + "\n").getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[body.length + tail.length];
        System.arraycopy(body, 0, out, 0, body.length);
        System.arraycopy(tail, 0, out, body.length, tail.length);
        return out;
    }

    private static void send(DatagramSocket socket, InetSocketAddress to, byte[] payload) throws IOException {
        socket.send(new DatagramPacket(payload, payload.length, to));
    }

    private static int freeUdpPort() throws IOException {
        try (DatagramSocket s = new DatagramSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static Path write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private void section(String title) {
        System.out.println();
        System.out.println("== " + title);
    }

    private void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  ok    " + name);
        } else {
            failures.add(name + (detail.isEmpty() ? "" : " — " + detail));
            System.out.println("  FAIL  " + name + (detail.isEmpty() ? "" : " — " + detail));
        }
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
    }

    private static long peakHeapBytes() {
        long peak = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                peak += pool.getPeakUsage().getUsed();
            }
        }
        return peak;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
