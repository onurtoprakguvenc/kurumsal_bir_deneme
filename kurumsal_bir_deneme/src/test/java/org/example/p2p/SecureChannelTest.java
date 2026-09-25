package org.example.p2p;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests of the AES-GCM frame tunnel: key agreement, round trips, and every way to tamper with frames. */
class SecureChannelTest {

    private static final int FRAME = SecureChannel.MAX_PLAINTEXT;

    /** A matching client/server key pair from a real X25519 exchange. */
    private static SecureChannel.Keys[] keys(byte[] transcript) throws Exception {
        KeyPair c = SecureChannel.ephemeral();
        KeyPair s = SecureChannel.ephemeral();
        byte[] sc = SecureChannel.agree(c, s.getPublic().getEncoded());
        byte[] ss = SecureChannel.agree(s, c.getPublic().getEncoded());
        return new SecureChannel.Keys[]{SecureChannel.derive(sc, transcript, true),
                SecureChannel.derive(ss, transcript, false)};
    }

    private static byte[] seal(SecureChannel.Keys sender, byte[]... writes) throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        OutputStream out = SecureChannel.output(wire, sender.send());
        for (byte[] w : writes) {
            out.write(w);
            out.flush();
        }
        return wire.toByteArray();
    }

    private static byte[] open(SecureChannel.Keys receiver, byte[] wire) throws IOException {
        InputStream in = SecureChannel.input(new ByteArrayInputStream(wire), receiver.receive());
        return in.readAllBytes();
    }

    /** Splits wire bytes into frames (length prefix included). */
    private static List<byte[]> frames(byte[] wire) {
        List<byte[]> out = new ArrayList<>();
        int p = 0;
        while (p < wire.length) {
            int len = ((wire[p] & 0xff) << 24) | ((wire[p + 1] & 0xff) << 16) | ((wire[p + 2] & 0xff) << 8)
                    | (wire[p + 3] & 0xff);
            out.add(Arrays.copyOfRange(wire, p, p + 4 + len));
            p += 4 + len;
        }
        return out;
    }

    private static byte[] join(List<byte[]> frames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        frames.forEach(out::writeBytes);
        return out.toByteArray();
    }

    private static byte[] random(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    @Test
    void bothSidesDeriveMatchingDirectionalKeys() throws Exception {
        SecureChannel.Keys[] k = keys("transcript".getBytes());
        assertArrayEquals(k[0].send().getEncoded(), k[1].receive().getEncoded());
        assertArrayEquals(k[0].receive().getEncoded(), k[1].send().getEncoded());
        assertFalse(Arrays.equals(k[0].send().getEncoded(), k[0].receive().getEncoded()), "one key per direction");
        assertEquals(32, k[0].send().getEncoded().length, "AES-256");
    }

    @Test
    void theTranscriptIsBoundIntoTheKeys() throws Exception {
        KeyPair c = SecureChannel.ephemeral();
        KeyPair s = SecureChannel.ephemeral();
        byte[] secret = SecureChannel.agree(c, s.getPublic().getEncoded());
        SecureChannel.Keys a = SecureChannel.derive(secret.clone(), "handshake A".getBytes(), true);
        SecureChannel.Keys b = SecureChannel.derive(secret.clone(), "handshake B".getBytes(), true);
        assertFalse(Arrays.equals(a.send().getEncoded(), b.send().getEncoded()));
    }

    @Test
    void theSharedSecretIsWipedAfterDerivation() throws Exception {
        KeyPair c = SecureChannel.ephemeral();
        byte[] secret = SecureChannel.agree(c, SecureChannel.ephemeral().getPublic().getEncoded());
        SecureChannel.derive(secret, new byte[0], true);
        assertArrayEquals(new byte[secret.length], secret);
    }

    @Test
    void lowOrderPointsAreRejected() throws Exception {
        KeyPair c = SecureChannel.ephemeral();
        byte[] encoded = c.getPublic().getEncoded();
        byte[] zeroPoint = Arrays.copyOf(encoded, encoded.length);
        Arrays.fill(zeroPoint, encoded.length - 32, encoded.length, (byte) 0);
        assertThrows(GeneralSecurityException.class, () -> SecureChannel.agree(c, zeroPoint));
    }

    @Test
    void roundTripAcrossFrameBoundariesAndWriteStyles() throws Exception {
        SecureChannel.Keys[] k = keys(new byte[0]);
        byte[] big = random(3 * FRAME + 12_345, 1);
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        OutputStream out = SecureChannel.output(wire, k[0].send());
        out.write(7);
        out.write(big, 0, 100);
        out.flush();
        out.write(big, 100, big.length - 100);
        out.write(8);
        out.close();
        byte[] plain = open(k[1], wire.toByteArray());
        assertEquals(big.length + 2, plain.length);
        assertEquals(7, plain[0]);
        assertArrayEquals(big, Arrays.copyOfRange(plain, 1, big.length + 1));
        assertEquals(8, plain[plain.length - 1]);
        for (byte[] f : frames(wire.toByteArray())) {
            assertTrue(f.length <= 4 + FRAME + SecureChannel.TAG_BYTES, "frames never exceed 64 KiB of plaintext");
        }
    }

    @Test
    void emptyFlushesSendNothing() throws Exception {
        SecureChannel.Keys[] k = keys(new byte[0]);
        assertEquals(0, seal(k[0], new byte[0], new byte[0]).length);
    }

    @Test
    void theWireCarriesNoPlaintext() throws Exception {
        SecureChannel.Keys[] k = keys(new byte[0]);
        byte[] secret = "GIZLI-MUHASEBE-RAPORU-2026.xlsx".repeat(100).getBytes(StandardCharsets.UTF_8);
        byte[] wire = seal(k[0], secret);
        String asText = new String(wire, StandardCharsets.ISO_8859_1);
        assertFalse(asText.contains("GIZLI"), "no plaintext on the wire");
        assertEquals(secret.length + 4 + SecureChannel.TAG_BYTES, wire.length, "one frame: header + ciphertext + tag");
    }

    @Test
    void identicalPlaintextFramesLookDifferent() throws Exception {
        SecureChannel.Keys[] k = keys(new byte[0]);
        byte[] block = new byte[1_000];
        List<byte[]> f = frames(seal(k[0], block, block));
        assertEquals(2, f.size());
        assertFalse(Arrays.equals(f.get(0), f.get(1)), "the frame counter gives every frame its own nonce");
    }

    @Test
    void everyAlteredByteIsDetected() throws Exception {
        SecureChannel.Keys[] k = keys(new byte[0]);
        byte[] wire = seal(k[0], random(500, 2));
        for (int i = 0; i < wire.length; i++) {
            byte[] bad = wire.clone();
            bad[i] ^= 0x01;
            int at = i;
            assertThrows(IOException.class, () -> open(k[1], bad), "flipped byte " + at + " must fail");
        }
    }

    @Test
    void replayedFramesAreRejected() throws Exception {
        SecureChannel.Keys[] k = keys(new byte[0]);
        List<byte[]> f = frames(seal(k[0], random(100, 3), random(100, 4)));
        IOException e = assertThrows(IOException.class, () -> open(k[1], join(List.of(f.get(0), f.get(0)))));
        assertTrue(e.getMessage().contains("authentication"), e.getMessage());
    }

    @Test
    void reorderedFramesAreRejected() throws Exception {
        SecureChannel.Keys[] k = keys(new byte[0]);
        List<byte[]> f = frames(seal(k[0], random(100, 5), random(100, 6)));
        assertThrows(IOException.class, () -> open(k[1], join(List.of(f.get(1), f.get(0)))));
    }

    @Test
    void droppedFramesAreRejected() throws Exception {
        SecureChannel.Keys[] k = keys(new byte[0]);
        List<byte[]> f = frames(seal(k[0], random(100, 7), random(100, 8), random(100, 9)));
        assertThrows(IOException.class, () -> open(k[1], join(List.of(f.get(0), f.get(2)))));
    }

    @Test
    void framesCannotBeReflectedBackToTheSender() throws Exception {
        SecureChannel.Keys[] k = keys(new byte[0]);
        byte[] fromClient = seal(k[0], random(100, 10));
        assertThrows(IOException.class, () -> open(k[0], fromClient), "the other direction uses another key");
    }

    @Test
    void framesFromAnotherSessionAreRejected() throws Exception {
        SecureChannel.Keys[] one = keys(new byte[0]);
        SecureChannel.Keys[] two = keys(new byte[0]);
        byte[] wire = seal(one[0], random(100, 11));
        assertThrows(IOException.class, () -> open(two[1], wire));
    }

    @Test
    void truncationInsideAFrameIsAnError() throws Exception {
        SecureChannel.Keys[] k = keys(new byte[0]);
        byte[] wire = seal(k[0], random(1_000, 12));
        assertThrows(EOFException.class, () -> open(k[1], Arrays.copyOf(wire, wire.length - 5)));
    }

    @Test
    void oversizedAndEmptyLengthPrefixesAreRejected() throws Exception {
        SecureChannel.Keys[] k = keys(new byte[0]);
        byte[] huge = {0x7f, 0, 0, 0};
        byte[] tagOnly = {0, 0, 0, (byte) SecureChannel.TAG_BYTES};
        assertThrows(IOException.class, () -> open(k[1], huge));
        assertThrows(IOException.class, () -> open(k[1], tagOnly));
    }

    @Test
    void aTamperedStreamNeverYieldsUnauthenticatedBytes() throws Exception {
        SecureChannel.Keys[] k = keys(new byte[0]);
        byte[] data = random(2 * FRAME, 13);
        byte[] wire = seal(k[0], data);
        wire[wire.length - 100] ^= 1; // inside the second frame
        InputStream in = SecureChannel.input(new ByteArrayInputStream(wire), k[1].receive());
        byte[] first = in.readNBytes(FRAME);
        assertArrayEquals(Arrays.copyOf(data, FRAME), first, "the intact first frame is delivered");
        assertThrows(IOException.class, in::read, "the altered frame is not");
    }

    @Test
    void differentPlaintextsProduceDifferentCiphertexts() throws Exception {
        SecureChannel.Keys[] k = keys(new byte[0]);
        assertNotEquals(Arrays.toString(seal(k[0], new byte[64])), Arrays.toString(seal(k[0], new byte[65])));
    }
}
