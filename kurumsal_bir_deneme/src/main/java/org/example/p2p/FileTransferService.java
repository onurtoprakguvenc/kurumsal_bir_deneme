package org.example.p2p;

import org.example.p2p.ContentStore.StoredFile;
import org.example.util.Hashing;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Content-addressed file transfer over plain TCP (one virtual thread per session).
 *
 * <p>Protocol v1 (big-endian), legacy mode ({@code DWB_TRUST=legacy}: optional {@code DWB_SECRET}):</p>
 * <pre>
 *   server → HELLO   "DWBT" ver:u8 nonce:16
 *   client → REQUEST "DWBT" ver:u8 op:u8 sha256:32 mac:32        mac = HMAC(key, nonce | op | sha256)
 *   PUT  client → size:u64 nameLen:u16 name
 *        server → HAVE | DENIED | BUSY | REJECT msg | READY offset:u64
 *        client → bytes[offset..size)          server → OK | CORRUPT msg
 *   GET  client → offset:u64
 *        server → NOT_FOUND | DENIED | FOUND size:u64 offset:u64 nameLen:u16 name, then bytes[offset..size)
 * </pre>
 *
 * <p>Protocol v2, zero-trust mode (the default, see {@link ZeroTrust}). Nothing about a file (no name, no hash, no
 * size) is sent before both devices proved their identity; any failure or a silence longer than
 * {@value #HANDSHAKE_TIMEOUT_MILLIS} ms closes the socket without a word:</p>
 * <pre>
 *   server → HELLO   "DWBT" 2 challengeS:32
 *   client → AUTH    "DWBT" 2 kind=1 key:blob challengeC:32 sig:64    sig = Ed25519(client, "client" | challengeS | challengeC)
 *            server: signature valid AND key in trusted-peers, else close()
 *   server → AUTH    key:blob sig:64                                  sig = Ed25519(server, "server" | challengeS | challengeC | clientKey)
 *            client: signature valid AND key in its own trusted-peers (and the expected device), else close()
 *   client → REQUEST req:blob sig:64                                  sig = Ed25519(client, "request" | challengeS | challengeC | req)
 *   CATALOG req = op                         server → count:u32 sha256:32…   (only what this device may see)
 *   GET     req = op sha256 offset:u64 tail:32
 *           server → close() | NOT_FOUND | FOUND size:u64 offset:u64 name, bytes[offset..size)
 *           client → OK | CORRUPT     (after verifying the SHA-256; uses up a guest's one-shot grant)
 *   PUT     req = op sha256 size:u64 name
 *           server → close() | HAVE | BUSY | REJECT msg | READY offset:u64 tail:32
 *           client → CONTINUE | RESTART, then bytes[offset..size)   server → OK | CORRUPT msg
 *   PAIR    see {@link Pairing}   (only while a pairing PIN is open, otherwise close())
 * </pre>
 * <p>{@code blob} is {@code len:u16 bytes}. {@code tail} is the SHA-256 of the last {@value #BLOCK} bytes before the
 * resume offset ({@code [offset - min(offset, BLOCK), offset)}): a resumed transfer continues only when both sides
 * hold the same bytes there, otherwise it starts over at 0, so bytes injected into a partial file are caught at the
 * handshake instead of after gigabytes. Signing the request binds operation, hash, offset and name to the
 * authenticated session. The transport itself is not encrypted in either protocol.</p>
 *
 * <p>The receiving side always re-hashes the complete file (resumed prefix included) and only commits bytes whose
 * SHA-256 equals the requested address; interrupted transfers keep their partial file and resume from its
 * length. Existing content is never transferred twice (HAVE).</p>
 *
 * <p>Memory is constant whatever the file size (text documents and multi-gigabyte media alike): bytes move
 * disk → socket and socket → disk through fixed {@value #BLOCK}-byte buffers, the digest is updated block by block,
 * and nothing ever holds the payload. Incoming bytes go to {@code partial/<sha256>.dwb_part} and are renamed into the
 * object store atomically only after the SHA-256 of the complete file matched; a mismatch deletes the partial file.
 * A partial file left by a dropped connection is kept (bounded by {@link #PARTIAL_MAX_AGE} and by the free disk
 * space) so the next attempt resumes instead of re-sending gigabytes.</p>
 */
public final class FileTransferService implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(FileTransferService.class.getName());

    public static final int DEFAULT_PORT = 47778;
    /** Largest single file accepted by default (64 GiB: room for long 4K video; memory use does not depend on it). */
    public static final long DEFAULT_MAX_FILE_BYTES = 64L << 30;

    static final byte[] MAGIC = {'D', 'W', 'B', 'T'};
    private static final int VERSION = 1;
    private static final int NONCE_BYTES = 16;
    private static final int BLOCK = 64 * 1024;
    /** Kernel socket buffers; larger than one block so a gigabit link stays saturated between reads. */
    private static final int SOCKET_BUFFER_BYTES = 1 << 20;
    /** Disk space that must stay free after an incoming file has been fully written. */
    private static final long DISK_RESERVE_BYTES = 256L << 20;
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int IO_TIMEOUT_MILLIS = 30_000;
    private static final int MAX_SESSIONS = 16;
    private static final int MAX_NAME_BYTES = 1_024;
    /** A receiver that accepts no bytes for this long is considered gone and the sending socket is closed. */
    private static final long SEND_STALL_MILLIS = 120_000;
    /**
     * A receiver re-hashes an already present prefix before it reads anything, so the first stall may last longer:
     * {@link #SEND_STALL_MILLIS} plus the prefix at this (slow disk) hashing rate.
     */
    private static final long PREFIX_HASH_BYTES_PER_SECOND = 20L << 20;
    /** Bytes that fit into the socket buffers before a receiver that is still hashing blocks the sender. */
    private static final long SEND_WARMUP_BYTES = 4L * SOCKET_BUFFER_BYTES;
    private static final long WATCHDOG_TICK_MILLIS = 1_000;
    /** Unfinished uploads older than this are deleted instead of being kept for resumption. */
    private static final java.time.Duration PARTIAL_MAX_AGE = java.time.Duration.ofHours(24);
    /** Upper bound for all partial files together (incoming uploads beyond it are rejected). */
    private static final long MAX_PARTIAL_BYTES = 128L << 30;

    private static final int OP_PUT = 1;
    private static final int OP_GET = 2;
    private static final int OP_CATALOG = 3;

    /** Zero-trust protocol (v2): challenge-response handshake with device keys. */
    static final int VERSION_ZERO_TRUST = 2;
    static final int CHALLENGE_BYTES = 32;
    /** Handshake (and pairing) must be complete within this time, whatever the peer sends meanwhile. */
    static final int HANDSHAKE_TIMEOUT_MILLIS = 10_000;
    static final int KIND_AUTH = 1;
    static final int KIND_PAIR = 2;
    private static final int MAX_REQUEST_BYTES = 2_048;
    private static final int MAX_CATALOG = 100_000;
    private static final int CONTINUE = 0;
    private static final int RESTART = 1;
    private static final byte[] LABEL_CLIENT = "dwbt2/client".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LABEL_SERVER = "dwbt2/server".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LABEL_REQUEST = "dwbt2/request".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NO_TAIL = new byte[Hashing.SHA256_BYTES];
    /** Refused devices are reported on the console at most this often each (the log gets every refusal). */
    private static final long DENIAL_REPORT_MILLIS = 5 * 60_000L;

    private static final int READY = 0;
    private static final int HAVE = 1;
    private static final int DENIED = 2;
    private static final int REJECT = 3;
    private static final int BUSY = 4;
    private static final int OK = 5;
    private static final int CORRUPT = 6;
    private static final int FOUND = 7;
    private static final int NOT_FOUND = 8;

    /** Invoked after a peer pushed a file to this node and it was verified and committed. */
    @FunctionalInterface
    public interface Listener {
        void received(StoredFile file, InetAddress from);
    }

    /**
     * Transfer progress callback: bytes of the file present at the receiver so far (resumed prefix included) and the
     * total file size. It is invoked on the transfer thread after every block, so implementations must only record
     * the values (see {@link TransferMeter}) and never block, print or lock.
     */
    @FunctionalInterface
    public interface Progress {
        void update(long transferred, long total);

        Progress NONE = (transferred, total) -> { };
    }

    public enum Outcome { SENT, RECEIVED, ALREADY_PRESENT }

    public record TransferResult(Outcome outcome, String sha256, long bytes, long resumedFrom, long millis,
                                 StoredFile file) {
    }

    /** Transfer failure with a readable reason (authentication, corruption, missing content, …). */
    public static final class TransferException extends IOException {
        private static final long serialVersionUID = 1L;

        public TransferException(String message) {
            super(message);
        }
    }

    /** The trusted device on the other end of a zero-trust session (and the session's challenges). */
    private record Session(TrustStore.Device device, byte[] challengeS, byte[] challengeC) {
    }

    /** Result of {@link #pair}: the device now trusted and the code both screens must show. */
    public record PairingResult(TrustStore.Device device, String verificationCode) {
    }

    private final ContentStore store;
    private final LanSecurity security;
    /** Zero-trust context; {@code null} in legacy mode (protocol v1). */
    private final ZeroTrust trust;
    private final int requestedPort;
    private final long maxFileBytes;
    private final Listener listener;
    private final Semaphore sessions = new Semaphore(MAX_SESSIONS);
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    /** One-shot grants being served right now ({@code sha256/fingerprint}): a grant is never served twice at once. */
    private final Set<String> oneShotInFlight = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> deniedReported = new ConcurrentHashMap<>();
    private volatile ServerSocket server;
    private volatile boolean running;
    private volatile java.util.function.Predicate<InetAddress> pushFilter = address -> true;

    /**
     * Decides which senders may push documents to this node (default: everyone). Addresses are only as trustworthy
     * as the network: with a shared secret ({@code DWB_SECRET}) only authenticated nodes get this far anyway. In
     * zero-trust mode it applies on top of the device check (guests can never push).
     */
    public void setPushFilter(java.util.function.Predicate<InetAddress> filter) {
        this.pushFilter = filter == null ? address -> true : filter;
    }

    /** Legacy mode (protocol v1): open, or authenticated by the pre-shared {@code DWB_SECRET}. */
    public FileTransferService(ContentStore store, LanSecurity security, int port, long maxFileBytes,
                               Listener listener) {
        this(store, security, null, port, maxFileBytes, listener);
    }

    /** Zero-trust mode (protocol v2): device keys, trusted-peers list, access lists, pairing. */
    public FileTransferService(ContentStore store, ZeroTrust trust, int port, long maxFileBytes, Listener listener) {
        this(store, LanSecurity.open(), Objects.requireNonNull(trust, "trust must not be null"), port, maxFileBytes,
                listener);
    }

    private FileTransferService(ContentStore store, LanSecurity security, ZeroTrust trust, int port,
                                long maxFileBytes, Listener listener) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.security = Objects.requireNonNull(security, "security must not be null");
        this.trust = trust;
        this.requestedPort = port;
        this.maxFileBytes = maxFileBytes;
        this.listener = listener == null ? (file, from) -> { } : listener;
    }

    /** Whether this service speaks the zero-trust protocol. */
    public boolean zeroTrust() {
        return trust != null;
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        store.purgeStalePartials(PARTIAL_MAX_AGE);
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.setReceiveBufferSize(SOCKET_BUFFER_BYTES); // inherited by accepted sockets, before bind for window scaling
        // Explicitly every IPv4 interface (0.0.0.0), never loopback only: peers on the LAN must be able to connect.
        s.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[4]), requestedPort), 50);
        server = s;
        running = true;
        List<String> lan = LanConsole.lanAddresses();
        LanConsole.listen("Listening on " + s.getInetAddress().getHostAddress() + ":" + s.getLocalPort()
                + " (TCP, all interfaces)" + (lan.isEmpty() ? "" : " · reachable as "
                + String.join(", ", lan.stream().map(a -> a + ":" + s.getLocalPort()).toList()))
                + " · inbound TCP " + s.getLocalPort() + " must be allowed by the firewall");
        Thread.ofVirtual().name("dwb-transfer-accept").start(this::acceptLoop);
    }

    public int port() {
        ServerSocket s = server;
        return s == null ? requestedPort : s.getLocalPort();
    }

    @Override
    public synchronized void close() {
        running = false;
        ServerSocket s = server;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing
            }
        }
    }

    // ================================================================== server

    private void acceptLoop() {
        while (running) {
            Socket client;
            try {
                client = server.accept();
            } catch (IOException e) {
                if (running) {
                    LOG.log(System.Logger.Level.WARNING, "Transfer accept failed: {0}", e.getMessage());
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                continue;
            }
            if (!sessions.tryAcquire()) {
                closeQuietly(client);
                continue;
            }
            Thread.ofVirtual().name("dwb-transfer-session").start(() -> {
                try (client) {
                    serve(client);
                } catch (IOException e) {
                    LOG.log(System.Logger.Level.DEBUG, "Transfer session with {0} ended: {1}",
                            client.getRemoteSocketAddress(), e.getMessage());
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.ERROR, "Transfer session failed", e);
                } finally {
                    sessions.release();
                }
            });
        }
    }

    private void serve(Socket socket) throws IOException {
        if (trust != null) {
            serveZeroTrust(socket);
            return;
        }
        tune(socket);
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), BLOCK));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), BLOCK));

        byte[] nonce = LanSecurity.nonce(NONCE_BYTES);
        out.write(MAGIC);
        out.writeByte(VERSION);
        out.write(nonce);
        out.flush();

        readMagic(in, VERSION);
        int op = in.readUnsignedByte();
        byte[] hash = in.readNBytes(Hashing.SHA256_BYTES);
        byte[] mac = in.readNBytes(LanSecurity.MAC_BYTES);
        if (hash.length != Hashing.SHA256_BYTES || mac.length != LanSecurity.MAC_BYTES) {
            throw new EOFException("truncated request");
        }
        if (!security.verify(mac, nonce, new byte[]{(byte) op}, hash)) {
            out.writeByte(DENIED);
            out.flush();
            finishRejected(socket, in);
            LOG.log(System.Logger.Level.WARNING, "Rejected unauthenticated transfer from {0}", socket.getInetAddress());
            LanConsole.failed("Rejected unauthenticated request from " + socket.getInetAddress().getHostAddress()
                    + " (DWB_SECRET missing or different)");
            return;
        }
        String sha256 = Hashing.hex(hash);
        switch (op) {
            case OP_PUT -> {
                long size = in.readLong();
                String name = readName(in);
                servePut(socket, in, out, sha256, size, name, null);
            }
            case OP_GET -> serveGet(socket, in, out, sha256, in.readLong(), null, null);
            default -> throw new IOException("unknown op " + op);
        }
    }

    /**
     * Zero-trust session: challenge-response handshake first; an unknown key, a bad signature, a malformed message or
     * a handshake slower than {@value #HANDSHAKE_TIMEOUT_MILLIS} ms ends in a plain {@code close()} (the caller's
     * try-with-resources), never in an explanation.
     */
    private void serveZeroTrust(Socket socket) throws IOException {
        tune(socket);
        socket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), BLOCK));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), BLOCK));
        Session session;
        byte[] request;
        try (Deadline ignored = Deadline.start(socket, HANDSHAKE_TIMEOUT_MILLIS)) {
            byte[] challengeS = LanSecurity.nonce(CHALLENGE_BYTES);
            out.write(MAGIC);
            out.writeByte(VERSION_ZERO_TRUST);
            out.write(challengeS);
            out.flush();

            byte[] magic = in.readNBytes(MAGIC.length);
            if (!Arrays.equals(magic, MAGIC) || in.readUnsignedByte() != VERSION_ZERO_TRUST) {
                denied(socket, null, "not a zero-trust client (a DWB_TRUST=legacy node, or not a DWBT peer)");
                return;
            }
            int kind = in.readUnsignedByte();
            if (kind == KIND_PAIR) {
                Pairing.serve(in, out, challengeS, trust, socket.getInetAddress());
                return;
            }
            if (kind != KIND_AUTH) {
                denied(socket, null, "unknown handshake");
                return;
            }
            byte[] clientKey = readBlob(in, DeviceIdentity.MAX_PUBLIC_KEY_BYTES);
            byte[] challengeC = readExact(in, CHALLENGE_BYTES);
            byte[] signature = readExact(in, DeviceIdentity.SIGNATURE_BYTES);
            Optional<TrustStore.Device> device = trust.trust().authenticate(clientKey);
            if (device.isEmpty()) {
                denied(socket, DeviceIdentity.fingerprintOf(clientKey), "device is not paired");
                return;
            }
            if (!DeviceIdentity.verify(clientKey, signature, LABEL_CLIENT, challengeS, challengeC)) {
                denied(socket, device.get().fingerprint(), "invalid challenge signature");
                return;
            }
            writeBlob(out, trust.identity().publicKey());
            out.write(trust.identity().sign(LABEL_SERVER, challengeS, challengeC, clientKey));
            out.flush();
            request = readBlob(in, MAX_REQUEST_BYTES);
            byte[] requestSignature = readExact(in, DeviceIdentity.SIGNATURE_BYTES);
            if (!DeviceIdentity.verify(clientKey, requestSignature, LABEL_REQUEST, challengeS, challengeC, request)) {
                denied(socket, device.get().fingerprint(), "invalid request signature");
                return;
            }
            session = new Session(device.get(), challengeS, challengeC);
        } catch (EOFException | SocketException e) {
            LOG.log(System.Logger.Level.DEBUG, "Handshake with {0} ended: {1}", socket.getInetAddress(), e.getMessage());
            return;
        }
        socket.setSoTimeout(IO_TIMEOUT_MILLIS);
        DataInputStream req = new DataInputStream(new ByteArrayInputStream(request));
        int op = req.readUnsignedByte();
        switch (op) {
            case OP_CATALOG -> serveCatalog(out, session);
            case OP_GET -> {
                String sha256 = Hashing.hex(readExact(req, Hashing.SHA256_BYTES));
                long offset = req.readLong();
                byte[] tail = readExact(req, Hashing.SHA256_BYTES);
                serveGet(socket, in, out, sha256, offset, tail, session);
            }
            case OP_PUT -> {
                String sha256 = Hashing.hex(readExact(req, Hashing.SHA256_BYTES));
                long size = req.readLong();
                String name = readName(req);
                servePut(socket, in, out, sha256, size, name, session);
            }
            default -> denied(socket, session.device().fingerprint(), "unknown operation " + op);
        }
    }

    /** Logs a refused zero-trust session; the socket is then closed by the caller without any answer. */
    private void denied(Socket socket, String fingerprint, String reason) {
        String from = socket.getInetAddress().getHostAddress();
        String who = fingerprint == null ? from : DeviceIdentity.display(fingerprint) + " at " + from;
        LOG.log(System.Logger.Level.INFO, "Closed session with {0}: {1}", who, reason);
        String key = fingerprint == null ? from : fingerprint;
        long now = System.currentTimeMillis();
        Long last = deniedReported.get(key);
        if (last == null || now - last > DENIAL_REPORT_MILLIS) {
            if (deniedReported.size() > 1_024) {
                deniedReported.clear();
            }
            deniedReported.put(key, now);
            LanConsole.failed("Refused " + who + ": " + reason + " (socket closed)");
        }
    }

    private void serveCatalog(DataOutputStream out, Session session) throws IOException {
        Set<String> visible = trust.catalogFor(store, session.device());
        int count = Math.min(visible.size(), MAX_CATALOG);
        out.writeInt(count);
        int written = 0;
        for (String sha : visible) {
            if (written++ == count) {
                break;
            }
            out.write(Hashing.fromHex(sha));
        }
        out.flush();
    }

    private void servePut(Socket socket, DataInputStream in, DataOutputStream out, String sha256, long size,
                          String name, Session session) throws IOException {
        if (session != null && session.device().guest()) {
            denied(socket, session.device().fingerprint(), "guests cannot push documents (" + name + ")");
            return;
        }
        if (size <= 0 || size > maxFileBytes) {
            out.writeByte(REJECT);
            writeText(out, "size " + size + " outside 1.." + maxFileBytes);
            out.flush();
            LanConsole.failed("Incoming " + name + " from " + socket.getInetAddress().getHostAddress()
                    + " refused: size " + size + " outside 1.." + maxFileBytes);
            return;
        }
        if (!pushFilter.test(socket.getInetAddress())) {
            out.writeByte(REJECT);
            writeText(out, "this node only accepts documents from its trusted peers");
            out.flush();
            LOG.log(System.Logger.Level.WARNING, "Refused a push from untrusted {0}", socket.getInetAddress());
            LanConsole.failed("Incoming " + name + " from " + socket.getInetAddress().getHostAddress()
                    + " refused: sender is not a trusted peer");
            return;
        }
        if (store.has(sha256)) {
            out.writeByte(HAVE);
            out.flush();
            return;
        }
        if (!inFlight.add(sha256)) {
            out.writeByte(BUSY);
            out.flush();
            return;
        }
        StoredFile committed = null;
        try {
            Path partial = store.partialPath(sha256);
            // Abandoned uploads are resumable for a while, but they must not be able to fill the disk.
            store.purgeStalePartials(PARTIAL_MAX_AGE);
            long existing = Files.exists(partial) ? Files.size(partial) : 0;
            if (store.partialBytes() - existing + size > MAX_PARTIAL_BYTES) {
                out.writeByte(REJECT);
                writeText(out, "receiver has too many unfinished transfers; retry later");
                out.flush();
                LanConsole.failed("Incoming " + name + " from " + socket.getInetAddress().getHostAddress()
                        + " refused: too many unfinished transfers");
                return;
            }
            long offset = existing;
            if (offset >= size) {
                Files.deleteIfExists(partial);
                offset = 0;
            }
            if (!enoughSpace(size - offset)) {
                out.writeByte(REJECT);
                writeText(out, "receiver has not enough free disk space for " + size + " bytes");
                out.flush();
                LanConsole.failed("Incoming " + name + " from " + socket.getInetAddress().getHostAddress()
                        + " refused: not enough free disk space for " + size + " bytes");
                return;
            }
            out.writeByte(READY);
            out.writeLong(offset);
            String from = socket.getInetAddress().getHostAddress();
            if (session != null) {
                // The sender compares the last block we already hold with its own file before it resumes.
                out.write(offset > 0 ? tailHash(partial, offset) : NO_TAIL);
                out.flush();
                int decision = in.readUnsignedByte();
                if (decision == RESTART) {
                    if (offset > 0) {
                        LanConsole.failed("Incoming " + name + " from " + from + ": the partial file's last block does"
                                + " not match the sender's; starting over at 0");
                    }
                    offset = 0;
                } else if (decision != CONTINUE) {
                    throw new IOException("unexpected resume decision " + decision);
                }
                from = session.device().name() + " (" + from + ")";
            } else {
                out.flush();
            }
            LanConsole.start("Receiving " + name + " (" + size + " bytes" + (offset > 0 ? ", resuming at " + offset : "")
                    + ") from " + from);
            byte[] digest;
            try {
                digest = receive(in, partial, size, offset, Progress.NONE);
            } catch (IOException e) {
                LanConsole.failed("Receiving " + name + " from " + from + ": " + LanConsole.reason(e));
                throw e;
            }
            if (Arrays.equals(digest, Hashing.fromHex(sha256)) && !complete(partial, size)) {
                Files.deleteIfExists(partial);
                out.writeByte(CORRUPT);
                writeText(out, "incomplete file on the receiver's disk");
                LanConsole.failed("Receiving " + name + " from " + from + ": file on disk incomplete, discarded");
            } else if (Arrays.equals(digest, Hashing.fromHex(sha256))) {
                committed = store.commit(partial, sha256, name);
                out.writeByte(OK);
            } else {
                Files.deleteIfExists(partial);
                out.writeByte(CORRUPT);
                writeText(out, "SHA-256 mismatch");
                LanConsole.failed("Receiving " + name + " from " + from + ": SHA-256 mismatch, discarded");
            }
            out.flush();
        } finally {
            inFlight.remove(sha256);
        }
        if (committed != null) {
            LOG.log(System.Logger.Level.INFO, "Received {0} ({1} bytes) from {2}", committed.name(), committed.size(),
                    socket.getInetAddress());
            LanConsole.success("Received " + committed.name() + " (" + committed.size() + " bytes, SHA-256 verified) from "
                    + socket.getInetAddress().getHostAddress() + ", stored at " + committed.path());
            listener.received(committed, socket.getInetAddress());
        }
    }

    private void serveGet(Socket socket, DataInputStream in, DataOutputStream out, String sha256, long requestedOffset,
                          byte[] tail, Session session) throws IOException {
        StoredFile file;
        String grant = null;
        if (session != null) {
            TrustStore.Device device = session.device();
            // Not visible to this device: closed like an unknown device, so it cannot even learn that the file exists.
            if (!trust.visible(store, sha256, device)) {
                denied(socket, device.fingerprint(), "may not pull " + sha256.substring(0, 12));
                return;
            }
            if (store.acl().oneShot(sha256, device)) {
                grant = sha256 + "/" + device.fingerprint();
                if (!oneShotInFlight.add(grant)) {
                    denied(socket, device.fingerprint(), "one-shot grant for " + sha256.substring(0, 12)
                            + " is already being used");
                    return;
                }
            }
            file = store.resolve(sha256).orElse(null);
        } else {
            file = store.isShared(sha256) ? store.resolve(sha256).orElse(null) : null;
        }
        try {
            if (file == null) {
                out.writeByte(NOT_FOUND);
                out.flush();
                return;
            }
            long offset = requestedOffset < 0 || requestedOffset >= file.size() ? 0 : requestedOffset;
            if (session != null && offset > 0 && !MessageDigest.isEqual(tail, tailHash(file.path(), offset))) {
                LanConsole.failed(session.device().name() + " asked to resume " + file.name() + " at " + offset
                        + " but its last block differs from ours; sending from 0");
                offset = 0;
            }
            out.writeByte(FOUND);
            out.writeLong(file.size());
            out.writeLong(offset);
            writeText(out, file.name());
            try (SendWatchdog watchdog = SendWatchdog.start(socket, offset)) {
                try {
                    send(file.path(), offset, file.size(), out, Progress.NONE, watchdog);
                    out.flush();
                } catch (IOException e) {
                    throw watchdog.explain(e);
                }
            }
            if (session != null) {
                int verdict = in.readUnsignedByte();
                if (verdict == OK && grant != null && store.acl().consumeOnce(sha256, session.device().fingerprint())) {
                    LanConsole.success("One-shot transfer of " + file.name() + " to guest " + session.device().name()
                            + " completed and verified; the grant is used up and the session is closed");
                }
            }
        } finally {
            if (grant != null) {
                oneShotInFlight.remove(grant);
            }
        }
    }

    // ================================================================== client

    /** Pushes locally available content to a peer. */
    public TransferResult push(InetSocketAddress peer, String sha256, Progress progress) throws IOException {
        return push(peer, null, sha256, progress);
    }

    /**
     * Pushes locally available content to a peer. In zero-trust mode the peer must prove it is a trusted device, and
     * {@code expectedDevice} (its fingerprint, when known) pins exactly which one: nothing about the file is sent
     * before that. Ignored in legacy mode.
     */
    public TransferResult push(InetSocketAddress peer, String expectedDevice, String sha256, Progress progress)
            throws IOException {
        StoredFile file = store.resolve(sha256)
                .orElseThrow(() -> new TransferException("Content " + sha256.substring(0, 12) + " is not available locally"));
        long started = System.nanoTime();
        try (Socket socket = connect(peer)) {
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), BLOCK));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), BLOCK));
            if (trust != null) {
                Session session = authenticate(socket, in, out, expectedDevice);
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                DataOutputStream req = new DataOutputStream(body);
                req.writeByte(OP_PUT);
                req.write(Hashing.fromHex(sha256));
                req.writeLong(file.size());
                writeText(req, file.name());
                sendRequest(out, session, body.toByteArray());
            } else {
                request(in, out, OP_PUT, sha256);
                out.writeLong(file.size());
                writeText(out, file.name());
            }
            out.flush();
            int status = readStatus(in, "closed the connection: it does not accept documents from this device");
            switch (status) {
                case HAVE -> {
                    return new TransferResult(Outcome.ALREADY_PRESENT, sha256, 0, 0, millisSince(started), file);
                }
                case READY -> {
                    long offset = in.readLong();
                    if (offset < 0 || offset >= file.size()) {
                        throw new TransferException("Peer requested invalid resume offset " + offset);
                    }
                    if (trust != null) {
                        byte[] theirTail = readExact(in, Hashing.SHA256_BYTES);
                        if (offset > 0 && !MessageDigest.isEqual(theirTail, tailHash(file.path(), offset))) {
                            // The receiver's partial file does not end with our bytes: never append to it.
                            out.writeByte(RESTART);
                            offset = 0;
                        } else {
                            out.writeByte(CONTINUE);
                        }
                    }
                    long sent;
                    try (SendWatchdog watchdog = SendWatchdog.start(socket, offset)) {
                        try {
                            sent = send(file.path(), offset, file.size(), out, progress, watchdog);
                            out.flush();
                        } catch (IOException e) {
                            throw watchdog.explain(e);
                        }
                    }
                    int verdict = in.readUnsignedByte();
                    if (verdict != OK) {
                        throw new TransferException("Peer rejected the upload: " + (verdict == CORRUPT ? readText(in) : "status " + verdict));
                    }
                    return new TransferResult(Outcome.SENT, sha256, sent, offset, millisSince(started), file);
                }
                default -> throw failure(status, in);
            }
        }
    }

    /**
     * Fetches content from a peer into the local store (resuming a previous partial download). The caller owns
     * post-processing of the returned file; the push {@link Listener} is not invoked.
     */
    public TransferResult pull(InetSocketAddress peer, String sha256, Progress progress) throws IOException {
        return pull(peer, null, sha256, progress);
    }

    /**
     * Fetches content from a peer, see {@link #pull(InetSocketAddress, String, Progress)}. In zero-trust mode the
     * peer must be a trusted device ({@code expectedDevice} pins which one), and a resume continues only when the
     * last block of the partial file matches the peer's bytes at that position.
     */
    public TransferResult pull(InetSocketAddress peer, String expectedDevice, String sha256, Progress progress)
            throws IOException {
        long started = System.nanoTime();
        StoredFile existing = store.resolve(sha256).orElse(null);
        if (existing != null) {
            return new TransferResult(Outcome.ALREADY_PRESENT, sha256, 0, 0, 0, existing);
        }
        if (!inFlight.add(sha256)) {
            throw new TransferException("A transfer of " + sha256.substring(0, 12) + " is already running");
        }
        StoredFile committed;
        long bytes;
        long offset;
        try (Socket socket = connect(peer)) {
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), BLOCK));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), BLOCK));
            Path partial = store.partialPath(sha256);
            long have = Files.exists(partial) ? Files.size(partial) : 0;
            if (trust != null) {
                Session session = authenticate(socket, in, out, expectedDevice);
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                DataOutputStream req = new DataOutputStream(body);
                req.writeByte(OP_GET);
                req.write(Hashing.fromHex(sha256));
                req.writeLong(have);
                req.write(have > 0 ? tailHash(partial, have) : NO_TAIL);
                sendRequest(out, session, body.toByteArray());
            } else {
                request(in, out, OP_GET, sha256);
                out.writeLong(have);
            }
            out.flush();
            int status = readStatus(in, "closed the connection: this device may not pull that document");
            if (status != FOUND) {
                throw failure(status, in);
            }
            long size = in.readLong();
            offset = in.readLong();
            String name = readText(in);
            if (size <= 0 || size > maxFileBytes || offset < 0 || offset > have) {
                throw new TransferException("Peer announced invalid size/offset (" + size + "/" + offset + ")");
            }
            if (!enoughSpace(size - offset)) {
                throw new TransferException("Not enough free disk space in " + store.root() + " for "
                        + (size - offset) + " more bytes");
            }
            if (offset == 0) {
                Files.deleteIfExists(partial);
            } else if (offset < have) {
                try (FileChannel channel = FileChannel.open(partial, StandardOpenOption.WRITE)) {
                    channel.truncate(offset);
                }
            }
            byte[] digest = receive(in, partial, size, offset, progress);
            bytes = size - offset;
            if (!Arrays.equals(digest, Hashing.fromHex(sha256))) {
                Files.deleteIfExists(partial);
                acknowledge(out, CORRUPT);
                throw new TransferException("Downloaded bytes do not match SHA-256 " + sha256.substring(0, 12) + "; discarded");
            }
            if (!complete(partial, size)) {
                Files.deleteIfExists(partial);
                acknowledge(out, CORRUPT);
                throw new TransferException("Downloaded file " + sha256.substring(0, 12)
                        + " is incomplete on disk (changed during the transfer); discarded");
            }
            committed = store.commit(partial, sha256, name);
            acknowledge(out, OK);
        } finally {
            inFlight.remove(sha256);
        }
        return new TransferResult(Outcome.RECEIVED, sha256, bytes, offset, millisSince(started), committed);
    }

    /** Zero-trust only: tells the sender whether the download verified (a guest's one-shot grant is used up on OK). */
    private void acknowledge(DataOutputStream out, int verdict) {
        if (trust == null) {
            return;
        }
        try {
            out.writeByte(verdict);
            out.flush();
        } catch (IOException e) {
            // the sender already hung up; the local result stands
        }
    }

    /**
     * Zero-trust only: the documents {@code expectedDevice} lets this device see (its full catalog for a colleague,
     * only the files granted to it for a guest).
     */
    public Set<String> catalog(InetSocketAddress peer, String expectedDevice) throws IOException {
        if (trust == null) {
            throw new IllegalStateException("catalog requests exist only in zero-trust mode");
        }
        try (Socket socket = connect(peer)) {
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), BLOCK));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), BLOCK));
            Session session = authenticate(socket, in, out, expectedDevice);
            sendRequest(out, session, new byte[]{(byte) OP_CATALOG});
            out.flush();
            int count = in.readInt();
            if (count < 0 || count > MAX_CATALOG) {
                throw new TransferException("Peer sent an invalid catalog size " + count);
            }
            Set<String> hashes = new LinkedHashSet<>();
            for (int i = 0; i < count; i++) {
                hashes.add(Hashing.hex(readExact(in, Hashing.SHA256_BYTES)));
            }
            return hashes;
        }
    }

    /**
     * Pairs this device with the device at {@code peer}, which must have a pairing PIN open ({@link
     * ZeroTrust#openPairing}). Both sides add each other to their trusted-peers list; the returned verification code
     * must equal the one the other device shows (otherwise remove the device again: someone was in between).
     *
     * @param localName name the other device stores for this one
     */
    public PairingResult pair(InetSocketAddress peer, String pin, String localName) throws IOException {
        if (trust == null) {
            throw new IllegalStateException("pairing exists only in zero-trust mode");
        }
        if (pin == null || !pin.strip().matches("\\d{6}")) {
            throw new IllegalArgumentException("the pairing PIN has 6 digits");
        }
        try (Socket socket = connect(peer)) {
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), BLOCK));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), BLOCK));
            byte[] challengeS = readHello(in);
            return Pairing.join(in, out, challengeS, trust, pin.strip(), localName);
        }
    }

    /** Whether {@code sha256} is visible to the device {@code fingerprint} under this node's rules (zero-trust). */
    public boolean visibleTo(String sha256, String fingerprint) {
        return trust != null && trust.trust().find(fingerprint).map(d -> trust.visible(store, sha256, d)).orElse(false);
    }

    /** Whether {@code incoming} more bytes fit on the store's disk with {@link #DISK_RESERVE_BYTES} to spare. */
    private boolean enoughSpace(long incoming) {
        long free = store.usableSpace();
        return free == Long.MAX_VALUE || free - incoming >= DISK_RESERVE_BYTES;
    }

    private static void tune(Socket socket) throws IOException {
        socket.setSoTimeout(IO_TIMEOUT_MILLIS);
        socket.setTcpNoDelay(true);
        socket.setSendBufferSize(SOCKET_BUFFER_BYTES);
        socket.setReceiveBufferSize(SOCKET_BUFFER_BYTES);
    }

    private Socket connect(InetSocketAddress peer) throws IOException {
        Socket socket = new Socket();
        try {
            socket.setReceiveBufferSize(SOCKET_BUFFER_BYTES); // before connect, so the TCP window scales
            socket.connect(peer, CONNECT_TIMEOUT_MILLIS);
            tune(socket);
            return socket;
        } catch (IOException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    private void request(DataInputStream in, DataOutputStream out, int op, String sha256) throws IOException {
        readMagic(in, VERSION);
        byte[] nonce = in.readNBytes(NONCE_BYTES);
        if (nonce.length != NONCE_BYTES) {
            throw new EOFException("truncated hello");
        }
        byte[] hash = Hashing.fromHex(sha256);
        out.write(MAGIC);
        out.writeByte(VERSION);
        out.writeByte(op);
        out.write(hash);
        out.write(security.mac(nonce, new byte[]{(byte) op}, hash));
    }

    /** Reads a zero-trust server's hello; returns its challenge. */
    private static byte[] readHello(DataInputStream in) throws IOException {
        readMagic(in, VERSION_ZERO_TRUST);
        return readExact(in, CHALLENGE_BYTES);
    }

    /**
     * Client half of the zero-trust handshake: proves this device's key, then checks the server's proof, its place
     * in this device's trusted-peers list and, when given, that it is exactly {@code expectedDevice}.
     */
    private Session authenticate(Socket socket, DataInputStream in, DataOutputStream out, String expectedDevice)
            throws IOException {
        socket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
        byte[] challengeS = readHello(in);
        byte[] challengeC = LanSecurity.nonce(CHALLENGE_BYTES);
        byte[] ownKey = trust.identity().publicKey();
        out.write(MAGIC);
        out.writeByte(VERSION_ZERO_TRUST);
        out.writeByte(KIND_AUTH);
        writeBlob(out, ownKey);
        out.write(challengeC);
        out.write(trust.identity().sign(LABEL_CLIENT, challengeS, challengeC));
        out.flush();
        byte[] serverKey;
        byte[] signature;
        try {
            serverKey = readBlob(in, DeviceIdentity.MAX_PUBLIC_KEY_BYTES);
            signature = readExact(in, DeviceIdentity.SIGNATURE_BYTES);
        } catch (EOFException | SocketException e) {
            throw new TransferException("Peer closed the connection during the handshake: this device ("
                    + DeviceIdentity.display(trust.identity().fingerprint()) + ") is not paired with it");
        }
        String fingerprint = DeviceIdentity.fingerprintOf(serverKey);
        TrustStore.Device server = trust.trust().authenticate(serverKey).orElseThrow(() -> new TransferException(
                "Peer " + DeviceIdentity.display(fingerprint) + " is not a trusted device; nothing was sent"));
        if (expectedDevice != null && !expectedDevice.equals(fingerprint)) {
            throw new TransferException("Peer identity mismatch: expected " + DeviceIdentity.display(expectedDevice)
                    + ", got " + DeviceIdentity.display(fingerprint) + " (" + server.name() + "); nothing was sent");
        }
        if (!DeviceIdentity.verify(serverKey, signature, LABEL_SERVER, challengeS, challengeC, ownKey)) {
            throw new TransferException("Peer " + server.name() + " failed the challenge (invalid signature); nothing"
                    + " was sent");
        }
        socket.setSoTimeout(IO_TIMEOUT_MILLIS);
        return new Session(server, challengeS, challengeC);
    }

    private void sendRequest(DataOutputStream out, Session session, byte[] request) throws IOException {
        writeBlob(out, request);
        out.write(trust.identity().sign(LABEL_REQUEST, session.challengeS(), session.challengeC(), request));
    }

    /** First status byte of a reply; in zero-trust mode a silent close means refusal ({@code refused}). */
    private int readStatus(DataInputStream in, String refused) throws IOException {
        try {
            return in.readUnsignedByte();
        } catch (EOFException | SocketException e) {
            if (trust != null) {
                throw new TransferException("Peer " + refused);
            }
            throw e;
        }
    }

    private static TransferException failure(int status, DataInputStream in) throws IOException {
        return switch (status) {
            case DENIED -> new TransferException("Peer denied access (shared secret missing or different)");
            case BUSY -> new TransferException("Peer is already receiving this document; retry shortly");
            case NOT_FOUND -> new TransferException("Peer does not have this document");
            case REJECT -> new TransferException("Peer rejected the request: " + readText(in));
            default -> new TransferException("Unexpected peer status " + status);
        };
    }

    // ================================================================== resume integrity

    /** SHA-256 of {@code file[offset - min(offset, BLOCK), offset)}: the last block before a resume offset. */
    static byte[] tailHash(Path file, long offset) throws IOException {
        MessageDigest digest = Hashing.sha256();
        long from = Math.max(0, offset - BLOCK);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            if (channel.size() < offset) {
                return NO_TAIL; // shorter than claimed: can never match a real tail
            }
            ByteBuffer buffer = ByteBuffer.allocate((int) (offset - from));
            long position = from;
            while (buffer.hasRemaining()) {
                int n = channel.read(buffer, position);
                if (n < 0) {
                    return NO_TAIL;
                }
                position += n;
            }
            buffer.flip();
            digest.update(buffer);
        }
        return digest.digest();
    }

    /**
     * Closes the socket when a zero-trust handshake is not finished in time. Socket timeouts alone would let a peer
     * that sends one byte every few seconds keep a session slot for minutes.
     */
    private static final class Deadline implements AutoCloseable {
        private final Thread thread;

        private Deadline(Socket socket, long millis) {
            this.thread = Thread.ofVirtual().name("dwb-transfer-deadline").unstarted(() -> {
                try {
                    Thread.sleep(millis);
                    closeQuietly(socket);
                } catch (InterruptedException e) {
                    // finished in time
                }
            });
        }

        static Deadline start(Socket socket, long millis) {
            Deadline d = new Deadline(socket, millis);
            d.thread.start();
            return d;
        }

        @Override
        public void close() {
            thread.interrupt();
        }
    }

    // ================================================================== streaming

    private static long send(Path file, long offset, long size, OutputStream out, Progress progress,
                             SendWatchdog watchdog) throws IOException {
        byte[] buffer = new byte[BLOCK];
        long sent = 0;
        long remaining = size - offset;
        try (InputStream in = Files.newInputStream(file)) {
            in.skipNBytes(offset);
            while (remaining > 0) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n < 0) {
                    throw new TransferException("Local file shrank during transfer");
                }
                out.write(buffer, 0, n);
                sent += n;
                remaining -= n;
                watchdog.accepted(sent);
                progress.update(offset + sent, size);
            }
        }
        return sent;
    }

    /**
     * Appends {@code size - offset} bytes to {@code partial}; returns the SHA-256 of the complete file.
     *
     * <p>The resumed prefix is hashed through the same open file as the new bytes are written to, and the partial's
     * modification time is refreshed first, so a concurrent {@link ContentStore#purgeStalePartials} cannot swap the
     * file underneath: the digest always describes the bytes that were actually written.</p>
     */
    private static byte[] receive(InputStream in, Path partial, long size, long offset, Progress progress)
            throws IOException {
        MessageDigest digest = Hashing.sha256();
        if (offset > 0) {
            try {
                Files.setLastModifiedTime(partial, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            } catch (IOException e) {
                // best effort: the size check before commit still catches a partial that vanished meanwhile
            }
        }
        byte[] buffer = new byte[BLOCK];
        long remaining = size - offset;
        long done = offset;
        try (FileChannel channel = FileChannel.open(partial, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            if (offset > 0) {
                if (channel.size() < offset) {
                    throw new TransferException("Partial file is shorter than its resume offset");
                }
                ByteBuffer prefix = ByteBuffer.wrap(buffer);
                long position = 0;
                while (position < offset) {
                    prefix.clear().limit((int) Math.min(buffer.length, offset - position));
                    int n = channel.read(prefix, position);
                    if (n < 0) {
                        throw new TransferException("Partial file is shorter than its resume offset");
                    }
                    digest.update(buffer, 0, n);
                    position += n;
                }
            }
            channel.truncate(offset); // same as the former TRUNCATE_EXISTING (offset 0) / APPEND at the offset
            channel.position(offset);
            while (remaining > 0) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n < 0) {
                    throw new EOFException("Connection closed after " + done + " of " + size + " bytes (partial kept for resume)");
                }
                ByteBuffer chunk = ByteBuffer.wrap(buffer, 0, n);
                while (chunk.hasRemaining()) {
                    channel.write(chunk);
                }
                digest.update(buffer, 0, n);
                remaining -= n;
                done += n;
                progress.update(done, size);
            }
        }
        return digest.digest();
    }

    /** Whether the partial on disk holds exactly {@code size} bytes (the digest alone cannot prove it). */
    private static boolean complete(Path partial, long size) {
        try {
            return Files.size(partial) == size;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Closes the socket of a send whose receiver stopped reading: socket writes have no timeout, so without it a
     * receiver that stays connected but never reads again would block {@code out.write} (and a session slot) forever.
     * The sending thread only stores two values per block; a virtual thread checks them once a second.
     */
    private static final class SendWatchdog implements AutoCloseable {
        private final Socket socket;
        private final long firstStallMillis;
        private final Thread thread;
        private volatile long sent;
        private volatile long lastProgressNanos = System.nanoTime();
        private volatile boolean fired;
        private volatile long firedAfterMillis;

        private SendWatchdog(Socket socket, long resumeOffset) {
            this.socket = socket;
            this.firstStallMillis = SEND_STALL_MILLIS + resumeOffset / PREFIX_HASH_BYTES_PER_SECOND * 1_000;
            this.thread = Thread.ofVirtual().name("dwb-transfer-watchdog").unstarted(this::watch);
        }

        static SendWatchdog start(Socket socket, long resumeOffset) {
            SendWatchdog watchdog = new SendWatchdog(socket, resumeOffset);
            watchdog.thread.start();
            return watchdog;
        }

        /** {@code total} bytes of this send were accepted by the socket so far. */
        void accepted(long total) {
            sent = total;
            lastProgressNanos = System.nanoTime();
        }

        private void watch() {
            try {
                while (true) {
                    Thread.sleep(WATCHDOG_TICK_MILLIS);
                    long limit = sent < SEND_WARMUP_BYTES ? firstStallMillis : SEND_STALL_MILLIS;
                    long stalled = (System.nanoTime() - lastProgressNanos) / 1_000_000;
                    if (stalled > limit) {
                        firedAfterMillis = stalled;
                        fired = true;
                        closeQuietly(socket);
                        return;
                    }
                }
            } catch (InterruptedException e) {
                // send finished
            }
        }

        /** The exception to report for {@code e}: a readable reason when the watchdog closed the socket. */
        IOException explain(IOException e) {
            if (!fired) {
                return e;
            }
            TransferException stalled = new TransferException("Receiver stopped reading for "
                    + firedAfterMillis / 1_000 + " s after " + sent + " bytes; connection closed");
            stalled.addSuppressed(e);
            return stalled;
        }

        @Override
        public void close() {
            thread.interrupt();
        }
    }

    // ================================================================== framing helpers

    private static void readMagic(DataInputStream in, int expected) throws IOException {
        byte[] magic = in.readNBytes(MAGIC.length);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IOException("Not a DWBT peer");
        }
        int version = in.readUnsignedByte();
        if (version == VERSION && expected == VERSION_ZERO_TRUST) {
            throw new TransferException("Peer runs the legacy LAN mode (DWB_TRUST=legacy); this node is in zero-trust"
                    + " mode - both sides must use the same mode");
        }
        if (version == VERSION_ZERO_TRUST && expected == VERSION) {
            throw new TransferException("Peer runs the zero-trust LAN mode; this node is in legacy mode"
                    + " (DWB_TRUST=legacy) - both sides must use the same mode");
        }
        if (version != expected) {
            throw new IOException("Unsupported protocol version " + version);
        }
    }

    /** {@code len:u16 bytes}, at most {@code max} bytes. */
    static byte[] readBlob(DataInputStream in, int max) throws IOException {
        int len = in.readUnsignedShort();
        if (len == 0 || len > max) {
            throw new IOException("field length " + len + " outside 1.." + max);
        }
        return readExact(in, len);
    }

    static void writeBlob(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    static byte[] readExact(DataInputStream in, int n) throws IOException {
        byte[] bytes = in.readNBytes(n);
        if (bytes.length != n) {
            throw new EOFException("truncated field");
        }
        return bytes;
    }

    static void writeText(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int len = Math.min(bytes.length, MAX_NAME_BYTES);
        while (len > 0 && len < bytes.length && (bytes[len] & 0xC0) == 0x80) {
            len--; // never cut inside a multi-byte UTF-8 sequence
        }
        out.writeShort(len);
        out.write(bytes, 0, len);
    }

    static String readText(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        if (len > MAX_NAME_BYTES) {
            throw new IOException("Text field too long: " + len);
        }
        byte[] bytes = in.readNBytes(len);
        if (bytes.length != len) {
            throw new EOFException("truncated text field");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String readName(DataInputStream in) throws IOException {
        return ContentStore.sanitizeName(readText(in));
    }

    /** Unread request bytes still queued when the server closes. */
    private static final int REJECT_DRAIN_BYTES = 64 * 1024;
    private static final int REJECT_DRAIN_MILLIS = 2_000;

    /**
     * Lets the client read a rejection: the client has already sent the rest of its request (offset, or size and
     * name), and closing a socket with unread input makes TCP send a reset that can discard the status byte before
     * the client reads it ("Connection reset" instead of the reason). Half-closes the output, then reads what is left
     * (bounded in bytes and time) until the client closes its side.
     */
    private static void finishRejected(Socket socket, InputStream in) {
        try {
            socket.shutdownOutput();
            socket.setSoTimeout(REJECT_DRAIN_MILLIS);
            long deadline = System.nanoTime() + REJECT_DRAIN_MILLIS * 1_000_000L;
            byte[] sink = new byte[4_096];
            long drained = 0;
            while (drained < REJECT_DRAIN_BYTES && System.nanoTime() < deadline) {
                int n = in.read(sink);
                if (n < 0) {
                    return;
                }
                drained += n;
            }
        } catch (IOException e) {
            // best effort: the client closed or reset first
        }
    }

    private static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // best effort
        }
    }
}
