package org.example;

import org.example.admin.AdminControlEngine;
import org.example.core.Workbench;
import org.example.index.InvertedIndex;
import org.example.ingest.DocumentParser;
import org.example.p2p.ContentStore;
import org.example.p2p.DeviceIdentity;
import org.example.p2p.DeviceRole;
import org.example.p2p.FileTransferService;
import org.example.p2p.FileTransferService.TransferException;
import org.example.p2p.FileTransferService.TransferResult;
import org.example.p2p.LanSecurity;
import org.example.p2p.LanSyncService;
import org.example.p2p.MachineKey;
import org.example.p2p.PeerDiscovery;
import org.example.p2p.TrustStore;
import org.example.p2p.ZeroTrust;
import org.example.platform.OsShellBridge;
import org.example.repl.InternalTerminalEngine;
import org.example.util.Hashing;
import org.example.workbench.ExtendedWorkbenchController;
import org.example.workbench.WorkbenchController;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * Headless verification of zero-trust LAN mode over loopback:
 *
 * <ol>
 *   <li>Device identity: Ed25519 key sealed to the machine (AES-GCM), fingerprint as id, foreign key set aside.</li>
 *   <li>Challenge-response handshake: unpaired devices, garbage and slow peers get a closed socket, nothing else.</li>
 *   <li>Pairing with a one-time PIN (X25519): wrong PIN voids it, roles and departments are stored.</li>
 *   <li>Access lists, catalog isolation and guests: department ACL, one-shot grants, guests never push.</li>
 *   <li>Resume integrity: a partial whose last block differs is never extended.</li>
 *   <li>Two synced nodes plus an unpaired one: signed announcements, no catalog on the wire, nothing reaches the
 *       unpaired node.</li>
 *   <li>Encrypted tunnel: no content, name or hash on the wire, tampering on the path caught, resume across
 *       tunnels, 256 MiB streamed under a 300 MB heap.</li>
 *   <li>Admin lock: opening a pairing PIN and revoking a device need an unlocked admin session (GUI and terminal).</li>
 * </ol>
 *
 * <pre>gradle -q runZeroTrustTests</pre>
 */
public final class ZeroTrustTestRunner {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static final MachineKey MACHINE = MachineKey.of("test-machine-0001");
    private static final long MAX = 1L << 30;

    private int passed;
    private final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        ZeroTrustTestRunner runner = new ZeroTrustTestRunner();
        Path root = Files.createTempDirectory("dwb-zt-");
        try {
            runner.identity(root.resolve("identity"));
            runner.handshakeAndPairing(root.resolve("pairing"));
            runner.endToEnd(root.resolve("e2e"));
            runner.tunnel(root.resolve("tunnel"));
            runner.adminLock(root.resolve("admin"));
        } finally {
            deleteRecursively(root);
        }
        System.out.println();
        System.out.printf("%d passed, %d failed%n", runner.passed, runner.failures.size());
        runner.failures.forEach(f -> System.out.println("  FAILED: " + f));
        System.exit(runner.failures.isEmpty() ? 0 : 1);
    }

    // ================================================================== 1. identity

    private void identity(Path dir) throws Exception {
        section("device identity: Ed25519 key sealed to the machine");
        DeviceIdentity first = DeviceIdentity.loadOrCreate(dir, MACHINE);
        check("the id is the SHA-256 fingerprint of the public key (64 hex)",
                first.fingerprint().matches("[0-9a-f]{64}")
                        && first.fingerprint().equals(DeviceIdentity.fingerprintOf(first.publicKey())), first.fingerprint());
        DeviceIdentity again = DeviceIdentity.loadOrCreate(dir, MACHINE);
        check("the same machine reopens the same identity", again.fingerprint().equals(first.fingerprint()), "");
        byte[] message = "imza".getBytes(StandardCharsets.UTF_8);
        check("signatures verify with the public key only",
                DeviceIdentity.verify(first.publicKey(), again.sign(message), message)
                        && !DeviceIdentity.verify(first.publicKey(), again.sign(message), "baska".getBytes()), "");
        byte[] file = Files.readAllBytes(dir.resolve(DeviceIdentity.KEY_FILE));
        check("the key file holds no plain PKCS#8 private key (only the public key and an AES-GCM blob)",
                indexOf(file, new byte[]{0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70}) < 0, "");
        DeviceIdentity moved = DeviceIdentity.loadOrCreate(dir, MachineKey.of("another-machine"));
        boolean setAside;
        try (Stream<Path> files = Files.list(dir)) {
            setAside = files.anyMatch(p -> p.getFileName().toString().startsWith(DeviceIdentity.KEY_FILE + ".foreign-"));
        }
        check("a key sealed on another machine cannot be opened: set aside, new identity, notice",
                !moved.fingerprint().equals(first.fingerprint()) && moved.notice().isPresent() && setAside, "");
        byte[] sealed = MACHINE.seal("gizli".getBytes(), "aad".getBytes());
        boolean tamperRejected;
        sealed[sealed.length - 1] ^= 1;
        try {
            MACHINE.open(sealed, "aad".getBytes());
            tamperRejected = false;
        } catch (java.security.GeneralSecurityException e) {
            tamperRejected = true;
        }
        check("AES-GCM rejects an altered sealed blob", tamperRejected, "");
        try {
            MachineKey detected = MachineKey.detect();
            check("this machine's OS identifier is readable (" + detected.source() + ")", true, "");
        } catch (IOException e) {
            check("this machine's OS identifier is readable", false, e.getMessage());
        }
    }

    // ================================================================== 2-5. handshake, pairing, ACL, guests, resume

    private record Node(String name, ZeroTrust trust, ContentStore store, FileTransferService service)
            implements AutoCloseable {
        InetSocketAddress endpoint() {
            return new InetSocketAddress(LOOPBACK, service.port());
        }

        String fp() {
            return trust.identity().fingerprint();
        }

        @Override
        public void close() {
            service.close();
        }
    }

    private Node node(Path dir, String name) throws IOException {
        ZeroTrust trust = ZeroTrust.load(dir.resolve("home"), MACHINE).withLocalName(name);
        ContentStore store = new ContentStore(dir.resolve("store"));
        FileTransferService service = new FileTransferService(store, trust, 0, MAX, null);
        service.start();
        return new Node(name, trust, store, service);
    }

    private void handshakeAndPairing(Path root) throws Exception {
        try (Node a = node(root.resolve("a"), "Muhasebe-PC");
             Node b = node(root.resolve("b"), "Finans-Laptop");
             Node c = node(root.resolve("c"), "Misafir");
             Node d = node(root.resolve("d"), "Dis-Makine")) {
            Path doc1 = write(root.resolve("files/butce.txt"), "2026 bütçe taslağı\n".repeat(200));
            Path doc2 = write(root.resolve("files/bordro.txt"), "bordro listesi\n".repeat(200));
            String sha1 = Hashing.sha256Hex(doc1);
            String sha2 = Hashing.sha256Hex(doc2);
            a.store().register(sha1, doc1, "butce.txt");
            a.store().register(sha2, doc2, "bordro.txt");

            section("handshake: a device that was never paired is refused");
            String error = pullError(b, a, sha1);
            check("an unpaired device cannot pull (socket closed during the handshake)",
                    error.contains("not paired"), error);
            check("nothing was written on the refused side", !b.store().has(sha1)
                    && !Files.exists(b.store().partialPath(sha1)), "");
            check("the refused device is not in the server's trust list", !a.trust().trust().trusted(b.fp()), "");
            check("the server says nothing to a client that does not complete the handshake",
                    rawClose(a, "DWBT\u0003\u0001garbage".getBytes(StandardCharsets.ISO_8859_1)), "");
            check("a legacy (v1) request gets no answer either",
                    rawClose(a, new byte[]{'D', 'W', 'B', 'T', 1, 2}), "");
            long started = System.nanoTime();
            boolean closed = rawSilent(a);
            long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            check("a silent client is disconnected after the handshake timeout (~10 s)", closed && waited < 13_000,
                    waited + " ms");
            try (FileTransferService legacy = new FileTransferService(b.store(), LanSecurity.open(), 0, MAX, null)) {
                String mixed = "";
                try {
                    legacy.pull(a.endpoint(), sha1, FileTransferService.Progress.NONE);
                } catch (IOException e) {
                    mixed = e.getMessage();
                }
                check("a legacy node talking to a zero-trust node gets a clear mode-mismatch message",
                        mixed.contains("zero-trust"), mixed);
            }

            section("pairing: one-time PIN over X25519");
            String wrong = pairError(b, a, "000000");
            check("pairing without an open PIN fails", wrong.contains("no pairing PIN"), wrong);
            ZeroTrust.PairingTicket ticket = a.trust().openPairing(DeviceRole.FULL_PEER, "finance");
            String badPin = ticket.pin().equals("123456") ? "654321" : "123456";
            String rejected = pairError(b, a, badPin);
            check("a wrong PIN is rejected and nothing is trusted", rejected.contains("rejected the PIN")
                    && !a.trust().trust().trusted(b.fp()) && !b.trust().trust().trusted(a.fp()), rejected);
            String reused = pairError(b, a, ticket.pin());
            check("after one failed attempt the PIN is void (no online guessing)", reused.contains("no pairing PIN"),
                    reused);
            ticket = a.trust().openPairing(DeviceRole.FULL_PEER, "finance");
            FileTransferService.PairingResult paired = b.service().pair(a.endpoint(), ticket.pin(), b.name());
            TrustStore.Device bOnA = a.trust().trust().find(b.fp()).orElse(null);
            check("the right PIN pairs both ways", bOnA != null && b.trust().trust().trusted(a.fp())
                    && paired.device().fingerprint().equals(a.fp()), "");
            check("role and department from the PIN are stored (role=FULL_PEER department=FINANCE)",
                    bOnA != null && bOnA.role() == DeviceRole.FULL_PEER && bOnA.department().equals("FINANCE"),
                    bOnA == null ? "" : bOnA.labels());
            check("a verification code is shown for comparison", paired.verificationCode().matches("\\d{3} \\d{3}"),
                    paired.verificationCode());
            check("a used PIN cannot pair a second device", pairError(d, a, ticket.pin()).contains("no pairing PIN"), "");
            ticket = a.trust().openPairing(DeviceRole.RESTRICTED_GUEST, "");
            c.service().pair(a.endpoint(), ticket.pin(), c.name());
            check("a guest can be paired with role=RESTRICTED_GUEST",
                    a.trust().trust().find(c.fp()).map(TrustStore.Device::guest).orElse(false), "");
            TrustStore reloaded = new TrustStore(a.trust().trust().file());
            check("the trusted-peers list survives a restart", reloaded.devices().size() == 2
                    && reloaded.find(b.fp()).map(TrustStore.Device::department).orElse("").equals("FINANCE"), "");

            section("access: catalog isolation, department ACL, identity pinning");
            Set<String> catalog = b.service().catalog(a.endpoint(), a.fp());
            check("a paired colleague receives the catalog over the authenticated channel",
                    catalog.equals(Set.of(sha1, sha2)), catalog.toString());
            TransferResult ok = b.service().pull(a.endpoint(), a.fp(), sha1, FileTransferService.Progress.NONE);
            check("a paired colleague pulls a shared document (SHA-256 verified)",
                    ok.outcome() == FileTransferService.Outcome.RECEIVED && b.store().has(sha1), ok.outcome().toString());
            String mismatch = "";
            try {
                b.service().pull(a.endpoint(), c.fp(), sha2, FileTransferService.Progress.NONE);
            } catch (IOException e) {
                mismatch = e.getMessage();
            }
            check("the client refuses a server that is not the device it expected",
                    mismatch.contains("identity mismatch") && !b.store().has(sha2), mismatch);
            a.store().acl().grant(sha2, "dept:HR");
            check("a document restricted to dept:HR leaves FINANCE's catalog",
                    !b.service().catalog(a.endpoint(), a.fp()).contains(sha2), "");
            String denied = pullError(b, a, sha2);
            check("pulling it is refused at socket level", denied.contains("may not pull") && !b.store().has(sha2),
                    denied);
            a.store().acl().grant(sha2, b.fp());
            check("authorized_keys: adding the device's key makes it visible again",
                    b.service().catalog(a.endpoint(), a.fp()).contains(sha2), "");
            a.store().acl().revoke(sha2, b.fp());

            section("guests: nothing but one-shot grants");
            check("a guest's catalog is empty", c.service().catalog(a.endpoint(), a.fp()).isEmpty(), "");
            String guestPull = pullError(c, a, sha1);
            check("a guest cannot pull a shared document", guestPull.contains("may not pull") && !c.store().has(sha1),
                    guestPull);
            a.store().acl().grant(sha1, ZeroTrust.grantEntry(a.trust().trust().find(c.fp()).orElseThrow()));
            check("after a grant the guest sees exactly that document",
                    c.service().catalog(a.endpoint(), a.fp()).equals(Set.of(sha1)), "");
            TransferResult once = c.service().pull(a.endpoint(), a.fp(), sha1, FileTransferService.Progress.NONE);
            waitFor(() -> a.store().acl().entries(sha1).isEmpty());
            check("the one-shot transfer succeeds and uses up the grant",
                    once.outcome() == FileTransferService.Outcome.RECEIVED && a.store().acl().entries(sha1).isEmpty(),
                    a.store().acl().entries(sha1).toString());
            check("afterwards the guest sees nothing again", c.service().catalog(a.endpoint(), a.fp()).isEmpty(), "");
            Path guestFile = write(root.resolve("files/misafir.txt"), "misafirden gelen\n".repeat(50));
            String guestSha = Hashing.sha256Hex(guestFile);
            c.store().register(guestSha, guestFile, "misafir.txt");
            String push = "";
            try {
                c.service().push(a.endpoint(), a.fp(), guestSha, FileTransferService.Progress.NONE);
            } catch (IOException e) {
                push = e.getMessage();
            }
            check("a guest cannot push documents", !push.isEmpty() && !a.store().has(guestSha), push);
            TransferResult toGuest = a.service().push(c.endpoint(), c.fp(), sha2, FileTransferService.Progress.NONE);
            check("a targeted send to a guest works (the guest trusts the sender)",
                    toGuest.outcome() == FileTransferService.Outcome.SENT && c.store().has(sha2), "");

            section("resume integrity: the last block is checked at the handshake");
            byte[] big = new byte[400_000];
            new Random(7).nextBytes(big);
            Path bigFile = root.resolve("files/video.bin");
            Files.write(bigFile, big);
            String bigSha = Hashing.sha256Hex(bigFile);
            a.store().register(bigSha, bigFile, "video.bin");
            Files.write(b.store().partialPath(bigSha), java.util.Arrays.copyOf(big, 250_000));
            TransferResult resumed = b.service().pull(a.endpoint(), a.fp(), bigSha, FileTransferService.Progress.NONE);
            check("an honest partial resumes where it stopped", resumed.resumedFrom() == 250_000
                    && Hashing.sha256Hex(resumed.file().path()).equals(bigSha), "resumed at " + resumed.resumedFrom());
            byte[] forged = java.util.Arrays.copyOf(big, 250_000);
            forged[249_000] ^= 0x55;
            Files.write(c.store().partialPath(bigSha), forged);
            a.store().acl().grant(bigSha, "once:" + c.fp());
            TransferResult restarted = c.service().pull(a.endpoint(), a.fp(), bigSha, FileTransferService.Progress.NONE);
            check("a partial with injected bytes in its last block is not extended: the pull starts over at 0",
                    restarted.resumedFrom() == 0 && Hashing.sha256Hex(restarted.file().path()).equals(bigSha),
                    "resumed at " + restarted.resumedFrom());
            byte[] other = new byte[300_000];
            new Random(8).nextBytes(other);
            Path otherFile = root.resolve("files/arsiv.bin");
            Files.write(otherFile, other);
            String otherSha = Hashing.sha256Hex(otherFile);
            b.store().register(otherSha, otherFile, "arsiv.bin");
            byte[] tampered = java.util.Arrays.copyOf(other, 200_000);
            tampered[199_999] ^= 1;
            Files.write(a.store().partialPath(otherSha), tampered);
            TransferResult pushed = b.service().push(a.endpoint(), a.fp(), otherSha, FileTransferService.Progress.NONE);
            check("a push onto a receiver-side partial with a foreign last block restarts at 0",
                    pushed.outcome() == FileTransferService.Outcome.SENT && pushed.resumedFrom() == 0
                            && a.store().has(otherSha), "resumed at " + pushed.resumedFrom());

            section("revocation");
            a.trust().forget(b.fp(), a.store());
            String revoked = pullError(b, a, sha2);
            check("a device removed from the trust list is refused again", revoked.contains("not paired"), revoked);
        }
    }

    // ================================================================== 6. two synced nodes and an outsider

    private record SyncNode(String name, Path project, WorkbenchController controller, LanSyncService sync,
                            ZeroTrust trust) implements AutoCloseable {
        @Override
        public void close() {
            sync.close();
            controller.close();
        }
    }

    private void endToEnd(Path root) throws Exception {
        section("zero-trust LAN sync: two paired nodes, one unpaired outsider");
        int[] ports = {freeUdpPort(), freeUdpPort(), freeUdpPort()};
        List<byte[]> sniffed = new CopyOnWriteArrayList<>();
        AtomicBoolean sniffing = new AtomicBoolean(true);
        try (DatagramSocket sniffer = new DatagramSocket(new InetSocketAddress(LOOPBACK, 0))) {
            sniffer.setSoTimeout(200);
            Thread.ofVirtual().start(() -> {
                byte[] buf = new byte[2_048];
                while (sniffing.get()) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        sniffer.receive(p);
                        sniffed.add(java.util.Arrays.copyOf(p.getData(), p.getLength()));
                    } catch (IOException e) {
                        // timeout: poll the flag
                    }
                }
            });
            InetSocketAddress snifferAt = new InetSocketAddress(LOOPBACK, sniffer.getLocalPort());
            List<SyncNode> nodes = new ArrayList<>();
            try {
                String[] names = {"Merkez", "Sube", "Yabanci"};
                for (int i = 0; i < 3; i++) {
                    List<InetSocketAddress> seeds = new ArrayList<>();
                    seeds.add(snifferAt);
                    for (int j = 0; j < 3; j++) {
                        if (j != i) {
                            seeds.add(new InetSocketAddress(LOOPBACK, ports[j]));
                        }
                    }
                    nodes.add(syncNode(root.resolve(names[i]), names[i], ports[i], seeds));
                }
                SyncNode a = nodes.get(0);
                SyncNode b = nodes.get(1);
                SyncNode x = nodes.get(2);
                ZeroTrust.PairingTicket ticket = a.trust().openPairing(DeviceRole.FULL_PEER, "");
                b.sync().pair(new InetSocketAddress(LOOPBACK, a.sync().status().transferPort()), ticket.pin());
                waitFor(() -> a.sync().status().peers().size() == 1 && b.sync().status().peers().size() == 1);
                check("paired nodes see each other", a.sync().status().peers().size() == 1
                        && b.sync().status().peers().size() == 1, "");
                check("the unpaired node sees no peer and is seen by none (blind and deaf)",
                        x.sync().status().peers().isEmpty() && a.sync().status().peers().stream()
                                .noneMatch(p -> p.nodeId().equals(x.trust().identity().fingerprint())), "");
                waitFor(() -> a.trust().unpaired().stream()
                        .anyMatch(s -> s.fingerprint().equals(x.trust().identity().fingerprint())));
                check("the unpaired node is only listed as a pending sighting", a.trust().unpaired().stream()
                        .anyMatch(s -> s.fingerprint().equals(x.trust().identity().fingerprint())), "");

                Path doc = write(root.resolve("src/gizli rapor.txt"), "Zümrütova çeyrek raporu, gizli.\n".repeat(40));
                String sha = Hashing.sha256Hex(doc);
                a.controller().addPath(doc, InternalTerminalEngine.Output.NONE);
                waitFor(() -> b.controller().document(sha).isPresent());
                check("a document added on A is fetched and indexed on paired B",
                        b.controller().document(sha).isPresent(), "");
                Thread.sleep(2_000);
                check("the unpaired node never receives it", x.controller().document(sha).isEmpty()
                        && !x.sync().store().has(sha), "");
                String allTraffic = sniffed.stream().map(p -> new String(p, StandardCharsets.UTF_8))
                        .reduce("", String::concat);
                check("announcements are signed (sig=) and carry the device key (pk=)",
                        allTraffic.contains("\nsig=") && allTraffic.contains("\npk=") && !allTraffic.contains("\nmac="),
                        "");
                check("no catalog on the wire: no HASH datagram, no document hash, no count",
                        !allTraffic.contains("t=HASH") && !allTraffic.contains(sha) && !allTraffic.contains("\ncnt="),
                        "");
                check("status reports zero-trust mode as authenticated", a.sync().status().authenticated()
                        && a.sync().status().mode().equals("zero-trust"), a.sync().status().mode());

                // The outsider becomes a guest of A: it may receive one granted file, and never feed A's index.
                ticket = a.trust().openPairing(DeviceRole.RESTRICTED_GUEST, "");
                x.sync().pair(new InetSocketAddress(LOOPBACK, a.sync().status().transferPort()), ticket.pin());
                Path guestDoc = write(root.resolve("guest-src/misafir notu.txt"), "Kırlangıçkuyruk teklif notu.\n".repeat(30));
                String guestSha = Hashing.sha256Hex(guestDoc);
                x.controller().addPath(guestDoc, InternalTerminalEngine.Output.NONE);
                waitFor(() -> a.sync().status().peers().stream()
                        .anyMatch(p -> p.nodeId().equals(x.trust().identity().fingerprint()) && p.hashes().contains(guestSha)));
                check("precondition: A knows the guest shares a document", a.sync().status().peers().stream()
                        .anyMatch(p -> p.hashes().contains(guestSha)), "");
                a.sync().syncNow();
                Thread.sleep(2_000);
                check("background sync never pulls a guest's documents into a full node",
                        a.controller().document(guestSha).isEmpty() && b.controller().document(guestSha).isEmpty(), "");
                check("the guest does not receive the shared document without a grant",
                        x.controller().document(sha).isEmpty(), "");
                a.sync().store().acl().grant(sha, "once:" + x.trust().identity().fingerprint());
                a.sync().catalogChanged();
                waitFor(() -> x.controller().document(sha).isPresent());
                check("a one-shot grant reaches the guest through its filtered catalog",
                        x.controller().document(sha).isPresent(), "");
                waitFor(() -> a.sync().store().acl().entries(sha).isEmpty());
                check("the grant is used up by that transfer", a.sync().store().acl().entries(sha).isEmpty(),
                        a.sync().store().acl().entries(sha).toString());
            } finally {
                sniffing.set(false);
                nodes.forEach(SyncNode::close);
            }
        }
    }

    private SyncNode syncNode(Path dir, String name, int discoveryPort, List<InetSocketAddress> seeds)
            throws IOException {
        Path project = Files.createDirectories(dir.resolve("project"));
        ZeroTrust trust = ZeroTrust.load(dir.resolve("home"), MACHINE);
        WorkbenchController controller = new WorkbenchController(
                new Workbench(new DocumentParser(), new InvertedIndex(), null),
                WorkbenchController.Config.defaults(project), new OsShellBridge());
        LanSyncService.Settings settings = new LanSyncService.Settings(null, name,
                InetAddress.getByName(PeerDiscovery.DEFAULT_GROUP), discoveryPort, 0, Duration.ofSeconds(1),
                Duration.ofSeconds(10), seeds, null, 0, trust);
        LanSyncService sync = new LanSyncService(settings, controller, project, null);
        sync.start();
        return new SyncNode(name, project, controller, sync, trust);
    }

    // ================================================================== 7. encrypted tunnel

    /**
     * A TCP relay between a client and a server on loopback: records what crosses it (up to a cap) and can flip one
     * byte of the server-to-client stream, like an attacker on the network path.
     */
    private static final class Relay implements AutoCloseable {
        private final java.net.ServerSocket listener = new java.net.ServerSocket(0, 50, LOOPBACK);
        private final InetSocketAddress target;
        private final java.io.ByteArrayOutputStream seen = new java.io.ByteArrayOutputStream();
        private final long tamperAt;
        private final int recordLimit;

        Relay(InetSocketAddress target, long tamperAt, int recordLimit) throws IOException {
            this.target = target;
            this.tamperAt = tamperAt;
            this.recordLimit = recordLimit;
            Thread.ofVirtual().start(this::acceptLoop);
        }

        InetSocketAddress endpoint() {
            return new InetSocketAddress(LOOPBACK, listener.getLocalPort());
        }

        synchronized byte[] seen() {
            return seen.toByteArray();
        }

        private void acceptLoop() {
            while (!listener.isClosed()) {
                try {
                    Socket client = listener.accept();
                    Socket server = new Socket();
                    server.connect(target, 2_000);
                    Thread.ofVirtual().start(() -> pump(client, server, -1));
                    Thread.ofVirtual().start(() -> pump(server, client, tamperAt));
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void pump(Socket from, Socket to, long flipAt) {
            byte[] buffer = new byte[16 * 1024];
            long total = 0;
            try (from; to) {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                int n;
                while ((n = in.read(buffer)) > 0) {
                    if (flipAt >= total && flipAt < total + n) {
                        buffer[(int) (flipAt - total)] ^= 0x20;
                    }
                    total += n;
                    synchronized (this) {
                        if (seen.size() < recordLimit) {
                            seen.write(buffer, 0, Math.min(n, recordLimit - seen.size()));
                        }
                    }
                    out.write(buffer, 0, n);
                }
            } catch (IOException e) {
                // one side closed
            }
        }

        @Override
        public void close() throws IOException {
            listener.close();
        }
    }

    private void tunnel(Path root) throws Exception {
        try (Node a = node(root.resolve("a"), "Sunucu");
             Node b = node(root.resolve("b"), "Istemci")) {
            ZeroTrust.PairingTicket ticket = a.trust().openPairing(DeviceRole.FULL_PEER, "");
            b.service().pair(a.endpoint(), ticket.pin(), b.name());

            section("encrypted tunnel: nothing readable on the wire");
            String marker = "KARTALKANAT-GIZLI-SOZLESME-";
            Path contract = write(root.resolve("files/Gizli Sozlesme Taslagi.txt"), (marker + "bedel 4.750.000 TL\n").repeat(5_000));
            String sha = Hashing.sha256Hex(contract);
            a.store().register(sha, contract, "Gizli Sozlesme Taslagi.txt");
            try (Relay sniffer = new Relay(a.endpoint(), -1, 8 << 20)) {
                Set<String> catalog = b.service().catalog(sniffer.endpoint(), a.fp());
                TransferResult pulled = b.service().pull(sniffer.endpoint(), a.fp(), sha, FileTransferService.Progress.NONE);
                check("pull through the tunnel verifies (SHA-256) and commits",
                        pulled.outcome() == FileTransferService.Outcome.RECEIVED && catalog.contains(sha)
                                && Hashing.sha256Hex(pulled.file().path()).equals(sha), "");
                Path upload = write(root.resolve("files/Maas Tablosu.csv"), ("personel;" + marker + "maas\n").repeat(3_000));
                String upSha = Hashing.sha256Hex(upload);
                b.store().register(upSha, upload, "Maas Tablosu.csv");
                TransferResult pushed = b.service().push(sniffer.endpoint(), a.fp(), upSha, FileTransferService.Progress.NONE);
                check("push through the tunnel verifies and commits", pushed.outcome() == FileTransferService.Outcome.SENT
                        && a.store().has(upSha), "");
                byte[] wire = sniffer.seen();
                String text = new String(wire, StandardCharsets.ISO_8859_1);
                String utf8 = new String(wire, StandardCharsets.UTF_8);
                check("the recording holds the whole exchange (sanity)", wire.length > 200_000, wire.length + " bytes");
                check("file contents never appear on the wire", !text.contains(marker) && !utf8.contains("bedel"), "");
                check("file names never appear on the wire", !utf8.contains("Gizli Sozlesme") && !utf8.contains("Maas Tablosu"),
                        "");
                check("content hashes (hex or raw) never appear on the wire", !text.contains(sha) && !text.contains(upSha)
                        && indexOf(wire, Hashing.fromHex(sha)) < 0 && indexOf(wire, Hashing.fromHex(upSha)) < 0, "");
            }

            section("encrypted tunnel: tampering on the path, then resume");
            byte[] big = new byte[3_000_000];
            new Random(21).nextBytes(big);
            Path video = root.resolve("files/kamera.mp4");
            Files.write(video, big);
            String videoSha = Hashing.sha256Hex(video);
            a.store().register(videoSha, video, "kamera.mp4");
            String error = "";
            try (Relay attacker = new Relay(a.endpoint(), 1_500_000, 0)) {
                b.service().pull(attacker.endpoint(), a.fp(), videoSha, FileTransferService.Progress.NONE);
            } catch (IOException e) {
                error = e.getMessage();
            }
            check("one flipped bit on the path breaks the frame authentication; nothing is committed",
                    error.contains("authentication") && !b.store().has(videoSha), error);
            Path partial = b.store().partialPath(videoSha);
            long kept = Files.exists(partial) ? Files.size(partial) : 0;
            boolean prefixIntact = kept > 0 && java.util.Arrays.equals(Files.readAllBytes(partial),
                    java.util.Arrays.copyOf(big, (int) kept));
            check("only authenticated bytes reached the disk (the partial is an exact prefix of the file)",
                    prefixIntact && kept < 1_500_000, kept + " bytes kept");
            TransferResult resumed = b.service().pull(a.endpoint(), a.fp(), videoSha, FileTransferService.Progress.NONE);
            check("the next pull resumes from the kept prefix through a new tunnel and verifies the whole file",
                    resumed.resumedFrom() == kept && Hashing.sha256Hex(resumed.file().path()).equals(videoSha),
                    "resumed at " + resumed.resumedFrom());
            String bad = "";
            try (Relay attacker = new Relay(a.endpoint(), 60, 0)) {
                b.service().catalog(attacker.endpoint(), a.fp());
            } catch (IOException e) {
                bad = e.getMessage();
            }
            check("tampering during the handshake is caught before anything is exchanged", !bad.isEmpty(), bad);

            section("encrypted tunnel: 256 MiB under the 300 MB heap ceiling");
            Path huge = root.resolve("files/arsiv-256.bin");
            byte[] chunk = new byte[1 << 20];
            new Random(22).nextBytes(chunk);
            try (OutputStream out = Files.newOutputStream(huge)) {
                for (int i = 0; i < 256; i++) {
                    chunk[0] = (byte) i;
                    out.write(chunk);
                }
            }
            String hugeSha = Hashing.sha256Hex(huge);
            a.store().register(hugeSha, huge, "arsiv-256.bin");
            System.gc();
            for (java.lang.management.MemoryPoolMXBean pool : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
                pool.resetPeakUsage();
            }
            long started = System.nanoTime();
            TransferResult hugePull = b.service().pull(a.endpoint(), a.fp(), hugeSha, FileTransferService.Progress.NONE);
            long millis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            long peak = 0;
            for (java.lang.management.MemoryPoolMXBean pool : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getType() == java.lang.management.MemoryType.HEAP) {
                    peak += pool.getPeakUsage().getUsed();
                }
            }
            long maxHeap = Runtime.getRuntime().maxMemory();
            check("256 MiB transferred through the tunnel and SHA-256 verified",
                    hugePull.outcome() == FileTransferService.Outcome.RECEIVED && hugePull.bytes() == 256L << 20, "");
            check("the JVM runs under the 300 MB ceiling (-Xmx300m)", maxHeap <= 300L << 20, (maxHeap >> 20) + " MB");
            check("peak heap during the transfer stays far below the ceiling (streaming, 64 KiB frames)",
                    peak < 100L << 20, (peak >> 20) + " MB");
            System.out.printf("        (%d MiB in %d ms = %.0f MB/s, both ends in this JVM, peak heap %d MB)%n", 256,
                    millis, 256.0 * 1_048_576 / 1e6 / (millis / 1000.0), peak >> 20);
            Files.deleteIfExists(huge);
            Files.deleteIfExists(hugePull.file().path());
        }
    }

    // ================================================================== 8. admin lock on pairing and revocation

    private void adminLock(Path root) throws Exception {
        section("admin lock: pairing and revocation need an unlocked admin session");
        Path home = Files.createDirectories(root.resolve("workspace"));
        Path identityHome = home.resolve("identity");
        ZeroTrust seed = ZeroTrust.load(identityHome, MACHINE);
        DeviceIdentity colleague = DeviceIdentity.loadOrCreate(root.resolve("colleague"), MACHINE);
        DeviceIdentity veteran = DeviceIdentity.loadOrCreate(root.resolve("veteran"), MACHINE);
        seed.trust().add(veteran.publicKey(), "Eski-Laptop", DeviceRole.FULL_PEER, "");
        // Back-date the veteran's pairing by an hour: established, not "just paired".
        Path list = identityHome.resolve(TrustStore.FILE);
        String text = Files.readString(list);
        String added = text.substring(text.lastIndexOf('\t') + 1).strip();
        Files.writeString(list, text.replace(added, Long.toString(Long.parseLong(added) - 3_600_000)));

        String token = "gizli-dagitim-anahtari";
        ExtendedWorkbenchController.Services services = new ExtendedWorkbenchController.Services(new DocumentParser(),
                null, java.util.Optional::empty, null, null, null, token);
        try (ExtendedWorkbenchController ext = new ExtendedWorkbenchController(
                ExtendedWorkbenchController.Config.defaults(home), services)) {
            ZeroTrust trust = ZeroTrust.load(identityHome, MACHINE);
            ext.enableLanSync(new LanSyncService.Settings(null, "Kilitli", InetAddress.getByName(PeerDiscovery.DEFAULT_GROUP),
                    freeUdpPort(), 0, Duration.ofSeconds(1), Duration.ofSeconds(10), List.of(), null, 0, trust));
            ext.start();
            waitFor(() -> ext.lanShield().kind() == ExtendedWorkbenchController.LanShieldKind.ZERO_TRUST);
            check("with an admin passphrase/token configured the session starts locked",
                    ext.admin().protectedMode() && !ext.lanAdminUnlocked(), "");

            String denied = privilegeError(() -> ext.lanOpenPairing(DeviceRole.FULL_PEER, ""));
            check("a locked session cannot open a pairing PIN", denied.contains("admin unlock")
                    && trust.pendingPairing().isEmpty(), denied);
            InternalTerminalEngine.Outcome viaTerminal = ext.terminal().execute("lan-pair --new");
            check("the terminal command is locked the same way (no bypass)", !viaTerminal.succeeded()
                    && trust.pendingPairing().isEmpty(), viaTerminal.toString());

            trust.trust().add(colleague.publicKey(), "Finans-Laptop", DeviceRole.FULL_PEER, "");
            String revokeDenied = privilegeError(() -> ext.lanRevoke("Finans-Laptop"));
            check("a locked session cannot revoke a trusted device", revokeDenied.contains("admin unlock")
                    && trust.trust().trusted(colleague.fingerprint()), revokeDenied);
            InternalTerminalEngine.Outcome removeViaTerminal = ext.terminal().execute("lan-devices --remove Finans-Laptop");
            check("lan-devices --remove is locked too", !removeViaTerminal.succeeded()
                    && trust.trust().trusted(colleague.fingerprint()), removeViaTerminal.toString());
            check("denials are recorded in the audit log", ext.admin().audit().tail(50).stream()
                    .anyMatch(l -> l.contains("SECURITY") && l.contains("lan-pair --new") && l.contains("denied")), "");

            // Privilege escalation paths: role, department, access lists.
            String roleDenied = privilegeError(() -> ext.lanSetRole("Eski-Laptop", DeviceRole.RESTRICTED_GUEST));
            check("a locked session cannot change a device's role", roleDenied.contains("admin unlock")
                    && trust.trust().find(veteran.fingerprint()).orElseThrow().role() == DeviceRole.FULL_PEER, roleDenied);
            trust.trust().setRole(veteran.fingerprint(), DeviceRole.RESTRICTED_GUEST);
            String escalate = privilegeError(() -> ext.lanSetRole("Eski-Laptop", DeviceRole.FULL_PEER));
            check("in particular a guest cannot be raised to FULL_PEER", escalate.contains("admin unlock")
                    && trust.trust().find(veteran.fingerprint()).orElseThrow().guest(), escalate);
            InternalTerminalEngine.Outcome roleViaTerminal = ext.terminal()
                    .execute("lan-devices --role Eski-Laptop FULL_PEER");
            check("lan-devices --role is locked too", !roleViaTerminal.succeeded()
                    && trust.trust().find(veteran.fingerprint()).orElseThrow().guest(), roleViaTerminal.toString());
            String deptDenied = privilegeError(() -> ext.lanSetDepartment("Eski-Laptop", "FINANCE"));
            InternalTerminalEngine.Outcome deptViaTerminal = ext.terminal().execute("lan-devices --dept Eski-Laptop HR");
            check("a locked session cannot change a department (controller and terminal)", deptDenied.contains(
                    "admin unlock") && !deptViaTerminal.succeeded()
                    && trust.trust().find(veteran.fingerprint()).orElseThrow().department().isEmpty(), deptDenied);
            String secretDoc = "c".repeat(64);
            String grantDenied = privilegeError(() -> ext.lanAcl(secretDoc, "Eski-Laptop", true));
            String deptGrantDenied = privilegeError(() -> ext.lanAcl(secretDoc, "dept:FINANCE", true));
            check("a locked session cannot grant access to a document", grantDenied.contains("admin unlock")
                    && deptGrantDenied.contains("admin unlock") && ext.lanAccessLists().isEmpty(), grantDenied);

            ext.lanRejectPairing(colleague.fingerprint());
            check("rejecting a pairing made moments ago (codes differed) works without the admin session",
                    !trust.trust().trusted(colleague.fingerprint()), "");
            String old = "";
            try {
                ext.lanRejectPairing(veteran.fingerprint());
            } catch (IllegalArgumentException e) {
                old = e.getMessage();
            }
            check("but it cannot remove an established device (that stays admin-locked)",
                    old.contains("last 5 minutes") && trust.trust().trusted(veteran.fingerprint()), old);

            check("a wrong passphrase does not unlock", ext.admin().unlock("yanlis".toCharArray())
                    instanceof AdminControlEngine.UnlockResult.Denied && !ext.lanAdminUnlocked(), "");
            check("the right passphrase unlocks", ext.admin().unlock(token.toCharArray())
                    instanceof AdminControlEngine.UnlockResult.Unlocked && ext.lanAdminUnlocked(), "");
            ZeroTrust.PairingTicket ticket = ext.lanOpenPairing(DeviceRole.RESTRICTED_GUEST, "");
            check("unlocked: a pairing PIN opens", ticket.pin().matches("\\d{6}") && trust.pendingPairing().isPresent(), "");
            ext.lanSetRole("Eski-Laptop", DeviceRole.FULL_PEER);
            ext.lanSetDepartment("Eski-Laptop", "finance");
            TrustStore.Device changed = trust.trust().find(veteran.fingerprint()).orElseThrow();
            check("unlocked: role and department change", changed.role() == DeviceRole.FULL_PEER
                    && changed.department().equals("FINANCE"), changed.labels());
            ext.lanAcl(secretDoc, "dept:FINANCE", true);
            check("unlocked: an access list is granted", ext.lanAccessLists().getOrDefault(secretDoc, "")
                    .contains("dept:FINANCE"), ext.lanAccessLists().toString());
            ext.admin().lock();
            String revokeAclDenied = privilegeError(() -> ext.lanAcl(secretDoc, "dept:FINANCE", false));
            check("locked again: revoking an access-list entry is refused (it would widen visibility)",
                    revokeAclDenied.contains("admin unlock") && ext.lanAccessLists().containsKey(secretDoc), revokeAclDenied);
            ext.admin().unlock(token.toCharArray());
            ext.lanAcl(secretDoc, "dept:FINANCE", false);
            check("unlocked: the access-list entry is revoked", ext.lanAccessLists().isEmpty(), "");
            ext.lanRevoke("Eski-Laptop");
            check("unlocked: an established device is revoked", !trust.trust().trusted(veteran.fingerprint()), "");
            ext.admin().lock();
            check("locking again closes both actions", privilegeError(() -> ext.lanOpenPairing(DeviceRole.FULL_PEER, ""))
                    .contains("admin unlock"), "");
        }
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    private static String privilegeError(Action action) {
        try {
            action.run();
            return "(allowed)";
        } catch (AdminControlEngine.PrivilegeException e) {
            return e.getMessage();
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    // ================================================================== helpers

    private static String pullError(Node client, Node server, String sha) {
        try {
            client.service().pull(server.endpoint(), server.fp(), sha, FileTransferService.Progress.NONE);
            return "(no error)";
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    private static String pairError(Node client, Node server, String pin) {
        try {
            client.service().pair(server.endpoint(), pin, client.name());
            return "(paired)";
        } catch (TransferException e) {
            return e.getMessage();
        } catch (IOException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** Sends {@code payload} after the hello and reports whether the server closed without writing anything. */
    private static boolean rawClose(Node server, byte[] payload) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(server.endpoint(), 2_000);
            s.setSoTimeout(15_000);
            DataInputStream in = new DataInputStream(s.getInputStream());
            in.readNBytes(4 + 1 + 32);
            OutputStream out = s.getOutputStream();
            out.write(payload);
            out.flush();
            return readsNothing(s.getInputStream());
        }
    }

    /** Connects, reads the hello, stays silent; true when the server hangs up without a word. */
    private static boolean rawSilent(Node server) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(server.endpoint(), 2_000);
            s.setSoTimeout(20_000);
            new DataInputStream(s.getInputStream()).readNBytes(4 + 1 + 32);
            return readsNothing(s.getInputStream());
        }
    }

    private static boolean readsNothing(InputStream in) {
        try {
            return in.read() < 0;
        } catch (IOException e) {
            return e instanceof java.net.SocketException; // reset: closed as well
        }
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i + pattern.length <= data.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
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
