package org.example.p2p;

import org.example.util.Hashing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real sockets on loopback (legacy protocol, so no device keys are needed; the zero-trust tunnel has its own suite):
 * streaming pull with progress, resume from a partial file, and refusal of bytes that do not match the address.
 */
class FileTransferLoopbackTest {

    @TempDir
    Path dir;

    private final List<FileTransferService> services = new ArrayList<>();

    private FileTransferService node(String name, LanSecurity security) throws IOException {
        ContentStore store = new ContentStore(dir.resolve(name));
        FileTransferService s = new FileTransferService(store, security, 0, 1L << 30, null);
        services.add(s);
        return s;
    }

    @AfterEach
    void close() {
        services.forEach(FileTransferService::close);
    }

    private static InetSocketAddress at(FileTransferService s) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), s.port());
    }

    private Path payload(String name, int size) throws IOException {
        byte[] bytes = new byte[size];
        new Random(size).nextBytes(bytes);
        return Files.write(dir.resolve(name), bytes);
    }

    private static ContentStore storeOf(FileTransferService s, Path root) throws IOException {
        return new ContentStore(root); // a second view of the same directories (registrations are per instance)
    }

    @Test
    void pullStreamsVerifiesAndReportsProgress() throws Exception {
        ContentStore serverStore = new ContentStore(dir.resolve("server"));
        FileTransferService server = new FileTransferService(serverStore, LanSecurity.open(), 0, 1L << 30, null);
        services.add(server);
        server.start();
        Path file = payload("video.bin", 5 * 1024 * 1024 + 17);
        String sha = Hashing.sha256Hex(file);
        serverStore.register(sha, file, "video.bin");

        FileTransferService client = node("client", LanSecurity.open());
        TransferMeter meter = new TransferMeter();
        FileTransferService.TransferResult r = client.pull(at(server), sha, meter);
        assertEquals(FileTransferService.Outcome.RECEIVED, r.outcome());
        assertEquals(Files.size(file), r.bytes());
        assertEquals(0, r.resumedFrom());
        assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(r.file().path()));
        assertEquals("video.bin", r.file().name());
        TransferMeter.Snapshot s = meter.snapshot();
        assertTrue(s.done(), "the meter saw every byte: " + s);
        assertEquals(Files.size(file), s.total());

        FileTransferService.TransferResult again = client.pull(at(server), sha, FileTransferService.Progress.NONE);
        assertEquals(FileTransferService.Outcome.ALREADY_PRESENT, again.outcome(), "content is never fetched twice");
    }

    @Test
    void interruptedDownloadResumesFromThePartialFile() throws Exception {
        ContentStore serverStore = new ContentStore(dir.resolve("server"));
        FileTransferService server = new FileTransferService(serverStore, LanSecurity.open(), 0, 1L << 30, null);
        services.add(server);
        server.start();
        Path file = payload("arsiv.bin", 3 * 1024 * 1024);
        String sha = Hashing.sha256Hex(file);
        serverStore.register(sha, file, "arsiv.bin");

        Path clientRoot = dir.resolve("client");
        FileTransferService client = node("client", LanSecurity.open());
        byte[] all = Files.readAllBytes(file);
        int have = 1_234_567;
        Files.write(storeOf(client, clientRoot).partialPath(sha), Arrays.copyOf(all, have));

        FileTransferService.TransferResult r = client.pull(at(server), sha, FileTransferService.Progress.NONE);
        assertEquals(FileTransferService.Outcome.RECEIVED, r.outcome());
        assertEquals(have, r.resumedFrom());
        assertEquals(all.length - have, r.bytes(), "only the missing tail travelled");
        assertArrayEquals(all, Files.readAllBytes(r.file().path()));
    }

    @Test
    void aTamperedPartialIsDiscardedAndNeverCommitted() throws Exception {
        ContentStore serverStore = new ContentStore(dir.resolve("server"));
        FileTransferService server = new FileTransferService(serverStore, LanSecurity.open(), 0, 1L << 30, null);
        services.add(server);
        server.start();
        Path file = payload("rapor.bin", 2 * 1024 * 1024);
        String sha = Hashing.sha256Hex(file);
        serverStore.register(sha, file, "rapor.bin");

        Path clientRoot = dir.resolve("client");
        FileTransferService client = node("client", LanSecurity.open());
        byte[] poisoned = Arrays.copyOf(Files.readAllBytes(file), 500_000);
        poisoned[1_000] ^= 0x55;
        Path partial = storeOf(client, clientRoot).partialPath(sha);
        Files.write(partial, poisoned);

        IOException e = assertThrows(IOException.class, () -> client.pull(at(server), sha, FileTransferService.Progress.NONE));
        assertTrue(e.getMessage().contains("do not match"), e.getMessage());
        assertFalse(Files.exists(partial), "the bad partial is deleted, so the next attempt starts clean");
        assertFalse(storeOf(client, clientRoot).has(sha));

        FileTransferService.TransferResult retry = client.pull(at(server), sha, FileTransferService.Progress.NONE);
        assertEquals(FileTransferService.Outcome.RECEIVED, retry.outcome());
        assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(retry.file().path()));
    }

    @Test
    void sharedSecretMismatchIsDenied() throws Exception {
        ContentStore serverStore = new ContentStore(dir.resolve("server"));
        FileTransferService server = new FileTransferService(serverStore, LanSecurity.fromSecret("dogru-parola-123"),
                0, 1L << 30, null);
        services.add(server);
        server.start();
        Path file = payload("gizli.bin", 100_000);
        String sha = Hashing.sha256Hex(file);
        serverStore.register(sha, file, "gizli.bin");

        FileTransferService wrong = node("wrong", LanSecurity.fromSecret("yanlis-parola-456"));
        IOException e = assertThrows(IOException.class, () -> wrong.pull(at(server), sha, FileTransferService.Progress.NONE));
        assertTrue(e.getMessage().contains("denied"), e.getMessage());

        FileTransferService right = node("right", LanSecurity.fromSecret("dogru-parola-123"));
        assertEquals(FileTransferService.Outcome.RECEIVED,
                right.pull(at(server), sha, FileTransferService.Progress.NONE).outcome());
    }

    @Test
    void servedTransfersAreObservedInBothDirections() throws Exception {
        ContentStore serverStore = new ContentStore(dir.resolve("server"));
        FileTransferService server = new FileTransferService(serverStore, LanSecurity.open(), 0, 1L << 30, null);
        services.add(server);
        List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        TransferMeter served = new TransferMeter();
        server.setServeObserver(new FileTransferService.ServeObserver() {
            @Override
            public FileTransferService.Progress started(boolean incoming, String sha256, String name, String peer,
                                                        long size) {
                events.add((incoming ? "in " : "out ") + name + " " + size);
                return served;
            }

            @Override
            public void finished(boolean incoming, String sha256, String peer, boolean verified) {
                events.add((incoming ? "in-done " : "out-done ") + verified);
            }
        });
        server.start();
        Path file = payload("dagitim.bin", 1_500_000);
        String sha = Hashing.sha256Hex(file);
        serverStore.register(sha, file, "dagitim.bin");

        FileTransferService client = node("client", LanSecurity.open());
        client.pull(at(server), sha, FileTransferService.Progress.NONE);
        waitUntil(() -> events.contains("out-done true"));
        assertEquals(List.of("out dagitim.bin 1500000", "out-done true"), events);
        assertEquals(1_500_000, served.snapshot().transferred(), "the server's own sink saw every byte it sent");

        events.clear();
        ContentStore clientStore = new ContentStore(dir.resolve("uploader"));
        FileTransferService uploader = new FileTransferService(clientStore, LanSecurity.open(), 0, 1L << 30, null);
        services.add(uploader);
        Path upload = payload("yukleme.bin", 800_000);
        String upSha = Hashing.sha256Hex(upload);
        clientStore.register(upSha, upload, "yukleme.bin");
        assertEquals(FileTransferService.Outcome.SENT,
                uploader.push(at(server), upSha, FileTransferService.Progress.NONE).outcome());
        waitUntil(() -> events.contains("in-done true"));
        assertEquals(List.of("in yukleme.bin 800000", "in-done true"), events);
        assertTrue(serverStore.has(upSha));
    }

    @Test
    void aFailingObserverNeverBreaksATransfer() throws Exception {
        ContentStore serverStore = new ContentStore(dir.resolve("server"));
        FileTransferService server = new FileTransferService(serverStore, LanSecurity.open(), 0, 1L << 30, null);
        services.add(server);
        server.setServeObserver((incoming, sha256, name, peer, size) -> (transferred, total) -> {
            throw new IllegalStateException("broken observer");
        });
        server.start();
        Path file = payload("saglam.bin", 300_000);
        String sha = Hashing.sha256Hex(file);
        serverStore.register(sha, file, "saglam.bin");
        FileTransferService client = node("client", LanSecurity.open());
        assertEquals(FileTransferService.Outcome.RECEIVED,
                client.pull(at(server), sha, FileTransferService.Progress.NONE).outcome());
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out");
            }
            Thread.sleep(10);
        }
    }

    @Test
    void unknownContentIsReportedAsMissing() throws Exception {
        FileTransferService server = node("server", LanSecurity.open());
        server.start();
        FileTransferService client = node("client", LanSecurity.open());
        String nothing = "0".repeat(64);
        IOException e = assertThrows(IOException.class, () -> client.pull(at(server), nothing, FileTransferService.Progress.NONE));
        assertTrue(e.getMessage().contains("does not have"), e.getMessage());
    }
}
