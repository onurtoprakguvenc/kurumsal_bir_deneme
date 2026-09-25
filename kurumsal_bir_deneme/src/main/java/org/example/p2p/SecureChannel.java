package org.example.p2p;

import org.example.util.Hashing;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/**
 * Encrypted tunnel of a zero-trust transfer session: AES-256-GCM frames over the TCP stream.
 *
 * <p><b>Keys.</b> Each side contributes an ephemeral X25519 key inside its signed handshake message, so the
 * Diffie-Hellman result is bound to both device identities and a man in the middle cannot substitute his own keys.
 * {@link #derive} runs HKDF-SHA256 (RFC 5869) over the shared secret with the handshake transcript as salt and
 * produces one 256-bit key per direction; the ephemeral private keys exist only for the session (forward secrecy).</p>
 *
 * <p><b>Frames.</b> {@code len:u32 | AES-GCM(ciphertext | tag:16)}, at most {@value #MAX_PLAINTEXT} plaintext bytes
 * each, i.e. the same 64 KiB granularity as the transfer buffers. The 96-bit nonce is a per-direction frame counter
 * (0, 1, 2, …) that is never sent: a replayed, dropped, reordered or altered frame fails the tag check, and the length
 * prefix is authenticated as additional data. Anything that fails closes the session with an {@link IOException};
 * nothing unauthenticated is ever handed to the caller.</p>
 *
 * <p>Memory per direction is two fixed 64 KiB buffers whatever the file size; bytes are encrypted and decrypted as
 * they stream, never collected.</p>
 */
final class SecureChannel {

    static final int MAX_PLAINTEXT = 64 * 1024;
    static final int TAG_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final String KEX = "X25519";
    private static final byte[] SALT_LABEL = "dwbt3/session-salt".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] INFO_C2S = "dwbt3/key client->server".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] INFO_S2C = "dwbt3/key server->client".getBytes(StandardCharsets.US_ASCII);

    /** The two directional AES-256 keys of one session, from one side's point of view. */
    record Keys(SecretKeySpec send, SecretKeySpec receive) {
    }

    private SecureChannel() {
    }

    // ------------------------------------------------------------------ key agreement

    static KeyPair ephemeral() throws IOException {
        try {
            return KeyPairGenerator.getInstance(KEX).generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IOException("X25519 is not available in this Java runtime", e);
        }
    }

    /** X25519 with the peer's X.509-encoded key; rejects low-order points (all-zero shared secret). */
    static byte[] agree(KeyPair own, byte[] peerEncoded) throws GeneralSecurityException {
        PublicKey peer = KeyFactory.getInstance(KEX).generatePublic(new X509EncodedKeySpec(peerEncoded));
        KeyAgreement ka = KeyAgreement.getInstance(KEX);
        ka.init(own.getPrivate());
        ka.doPhase(peer, true);
        byte[] secret = ka.generateSecret();
        int acc = 0;
        for (byte b : secret) {
            acc |= b;
        }
        if (acc == 0) {
            throw new java.security.InvalidKeyException("low-order X25519 point");
        }
        return secret;
    }

    /**
     * HKDF-SHA256: extract with {@code salt = SHA-256(label | transcript)}, expand one 32-byte key per direction.
     * The shared secret is wiped afterwards.
     */
    static Keys derive(byte[] sharedSecret, byte[] transcript, boolean client) {
        try {
            byte[] salt = Hashing.sha256().digest(DeviceIdentity.transcript(SALT_LABEL, transcript));
            byte[] prk = hmac(salt, sharedSecret);
            SecretKeySpec c2s = new SecretKeySpec(expand(prk, INFO_C2S), "AES");
            SecretKeySpec s2c = new SecretKeySpec(expand(prk, INFO_S2C), "AES");
            Arrays.fill(prk, (byte) 0);
            return client ? new Keys(c2s, s2c) : new Keys(s2c, c2s);
        } finally {
            Arrays.fill(sharedSecret, (byte) 0);
        }
    }

    /** HKDF-Expand for L = 32 (a single block: {@code T(1) = HMAC(prk, info | 0x01)}). */
    private static byte[] expand(byte[] prk, byte[] info) {
        byte[] input = Arrays.copyOf(info, info.length + 1);
        input[info.length] = 1;
        return hmac(prk, input);
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    // ------------------------------------------------------------------ streams

    static OutputStream output(OutputStream raw, SecretKeySpec key) {
        return new FrameOutput(raw, key);
    }

    static InputStream input(InputStream raw, SecretKeySpec key) {
        return new FrameInput(raw, key);
    }

    private static Cipher gcm() {
        try {
            return Cipher.getInstance("AES/GCM/NoPadding");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM unavailable", e);
        }
    }

    /** The frame counter as a 96-bit nonce: 4 zero bytes, then the counter big-endian. */
    private static byte[] nonce(long counter) {
        byte[] n = new byte[NONCE_BYTES];
        for (int i = 0; i < 8; i++) {
            n[NONCE_BYTES - 1 - i] = (byte) (counter >>> (8 * i));
        }
        return n;
    }

    private static byte[] header(int length) {
        return new byte[]{(byte) (length >>> 24), (byte) (length >>> 16), (byte) (length >>> 8), (byte) length};
    }

    /** Collects plaintext into one 64 KiB frame; a full buffer or {@link #flush()} seals and sends it. */
    private static final class FrameOutput extends OutputStream {
        private final OutputStream raw;
        private final SecretKeySpec key;
        private final Cipher cipher = gcm();
        private final byte[] plain = new byte[MAX_PLAINTEXT];
        private final byte[] frame = new byte[4 + MAX_PLAINTEXT + TAG_BYTES];
        private int used;
        private long counter;
        private boolean closed;

        FrameOutput(OutputStream raw, SecretKeySpec key) {
            this.raw = raw;
            this.key = key;
        }

        @Override
        public void write(int b) throws IOException {
            if (used == plain.length) {
                seal();
            }
            plain[used++] = (byte) b;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            java.util.Objects.checkFromIndexSize(off, len, b.length);
            while (len > 0) {
                if (used == plain.length) {
                    seal();
                }
                int n = Math.min(len, plain.length - used);
                System.arraycopy(b, off, plain, used, n);
                used += n;
                off += n;
                len -= n;
            }
        }

        private void seal() throws IOException {
            if (closed) {
                throw new IOException("encrypted channel is closed");
            }
            if (used == 0) {
                return;
            }
            if (counter == Long.MAX_VALUE) {
                throw new IOException("encrypted channel exhausted its frame counter");
            }
            int length = used + TAG_BYTES;
            byte[] head = header(length);
            try {
                cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, nonce(counter)));
                cipher.updateAAD(head);
                int n = cipher.doFinal(plain, 0, used, frame, 4);
                if (n != length) {
                    throw new IOException("unexpected AES-GCM output length " + n);
                }
            } catch (GeneralSecurityException e) {
                throw new IOException("AES-GCM encryption failed", e);
            }
            System.arraycopy(head, 0, frame, 0, 4);
            counter++;
            used = 0;
            raw.write(frame, 0, 4 + length);
        }

        @Override
        public void flush() throws IOException {
            seal();
            raw.flush();
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                flush();
                closed = true;
                raw.close();
            }
        }
    }

    /** Reads, authenticates and decrypts one frame at a time; hands out only verified plaintext. */
    private static final class FrameInput extends InputStream {
        private final DataInputStream raw;
        private final SecretKeySpec key;
        private final Cipher cipher = gcm();
        private final byte[] plain = new byte[MAX_PLAINTEXT];
        private final byte[] sealed = new byte[MAX_PLAINTEXT + TAG_BYTES];
        private int position;
        private int limit;
        private long counter;
        private boolean eof;

        FrameInput(InputStream raw, SecretKeySpec key) {
            this.raw = raw instanceof DataInputStream d ? d : new DataInputStream(raw);
            this.key = key;
        }

        /** Loads the next frame; false at a clean end of stream (between frames). */
        private boolean next() throws IOException {
            if (eof) {
                return false;
            }
            int b0 = raw.read();
            if (b0 < 0) {
                eof = true;
                return false;
            }
            int length = (b0 << 24) | (raw.readUnsignedByte() << 16) | (raw.readUnsignedByte() << 8)
                    | raw.readUnsignedByte();
            if (length <= TAG_BYTES || length > sealed.length) {
                throw new IOException("encrypted frame of invalid length " + length + "; connection closed");
            }
            try {
                raw.readFully(sealed, 0, length);
            } catch (EOFException e) {
                throw new EOFException("connection closed inside an encrypted frame");
            }
            try {
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, nonce(counter)));
                cipher.updateAAD(header(length));
                limit = cipher.doFinal(sealed, 0, length, plain, 0);
            } catch (AEADBadTagException e) {
                throw new IOException("encrypted frame failed authentication (altered, replayed, reordered or"
                        + " dropped); connection closed");
            } catch (GeneralSecurityException e) {
                throw new IOException("AES-GCM decryption failed", e);
            }
            counter++;
            position = 0;
            return true;
        }

        @Override
        public int read() throws IOException {
            while (position == limit) {
                if (!next()) {
                    return -1;
                }
            }
            return plain[position++] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            java.util.Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) {
                return 0;
            }
            while (position == limit) {
                if (!next()) {
                    return -1;
                }
            }
            int n = Math.min(len, limit - position);
            System.arraycopy(plain, position, b, off, n);
            position += n;
            return n;
        }

        @Override
        public int available() {
            return limit - position;
        }

        @Override
        public void close() throws IOException {
            raw.close();
        }
    }
}
