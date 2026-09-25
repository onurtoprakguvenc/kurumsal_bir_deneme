package org.example.p2p;

import org.example.util.Hashing;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Optional;

/**
 * This device's cryptographic identity: an Ed25519 key pair created on first start.
 *
 * <p>The device's id on the network is the fingerprint of its public key, {@code hex(SHA-256(X.509 public key))}
 * (64 characters), instead of a random string. The private key never touches the disk in the clear: it is sealed with
 * {@link MachineKey} (AES-256-GCM, key derived from the operating system's machine identifier) into
 * {@code device.key}. The file is useless on another computer; a device key found sealed to another machine (a
 * cloned disk image, a copied profile) is set aside as {@code device.key.foreign-<time>} and a fresh identity is
 * created, which then has to be paired again.</p>
 *
 * <p>Every signature covers a length-prefixed list of parts ({@link #transcript}), so concatenations of different
 * fields can never produce the same signed bytes.</p>
 */
public final class DeviceIdentity {

    private static final System.Logger LOG = System.getLogger(DeviceIdentity.class.getName());

    public static final String KEY_FILE = "device.key";
    public static final String ALGORITHM = "Ed25519";
    public static final int SIGNATURE_BYTES = 64;
    /** Upper bound for an encoded public key on the wire (Ed25519 X.509 encodings are 44 bytes). */
    public static final int MAX_PUBLIC_KEY_BYTES = 128;

    private static final byte[] FILE_MAGIC = {'D', 'W', 'B', 'K', 1};

    private final PrivateKey privateKey;
    private final byte[] publicKey;
    private final String fingerprint;
    private final String notice;

    private DeviceIdentity(PrivateKey privateKey, byte[] publicKey, String notice) {
        this.privateKey = privateKey;
        this.publicKey = publicKey.clone();
        this.fingerprint = fingerprintOf(publicKey);
        this.notice = notice;
    }

    /**
     * Loads the identity kept in {@code dir}, or creates one. Blocking (disk, key generation).
     *
     * @throws IOException when the directory is not writable or the key cannot be read for another reason than being
     *                     sealed to another machine
     */
    public static DeviceIdentity loadOrCreate(Path dir, MachineKey machine) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(KEY_FILE);
        String notice = null;
        if (Files.isRegularFile(file)) {
            try {
                return read(file, machine);
            } catch (GeneralSecurityException e) {
                Path aside = file.resolveSibling(KEY_FILE + ".foreign-" + System.currentTimeMillis());
                Files.move(file, aside);
                notice = "the device key in " + file + " was sealed on another machine (cloned or copied); it was"
                        + " moved to " + aside.getFileName() + " and a new identity was created - pair this device again";
                LOG.log(System.Logger.Level.WARNING, notice);
            }
        }
        KeyPair pair;
        try {
            pair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IOException("Ed25519 is not available in this Java runtime", e);
        }
        byte[] pub = pair.getPublic().getEncoded();
        write(file, pub, machine.seal(pair.getPrivate().getEncoded(), aad(pub)));
        return new DeviceIdentity(pair.getPrivate(), pub, notice);
    }

    private static DeviceIdentity read(Path file, MachineKey machine) throws IOException, GeneralSecurityException {
        byte[] pub;
        byte[] sealed;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            byte[] magic = in.readNBytes(FILE_MAGIC.length);
            if (!java.util.Arrays.equals(magic, FILE_MAGIC)) {
                throw new IOException(file + " is not a device key file");
            }
            int pubLen = in.readUnsignedShort();
            if (pubLen < 1 || pubLen > MAX_PUBLIC_KEY_BYTES) {
                throw new IOException(file + " is damaged");
            }
            pub = in.readNBytes(pubLen);
            int sealedLen = in.readInt();
            if (sealedLen < 1 || sealedLen > 4_096) {
                throw new IOException(file + " is damaged");
            }
            sealed = in.readNBytes(sealedLen);
            if (pub.length != pubLen || sealed.length != sealedLen) {
                throw new IOException(file + " is truncated");
            }
        }
        byte[] pkcs8 = machine.open(sealed, aad(pub));
        try {
            PrivateKey priv = KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            publicKey(pub); // well-formed
            DeviceIdentity identity = new DeviceIdentity(priv, pub, null);
            byte[] probe = LanSecurity.nonce(32);
            if (!verify(pub, identity.sign(probe), probe)) {
                throw new IOException(file + ": private and public key do not belong together");
            }
            return identity;
        } finally {
            java.util.Arrays.fill(pkcs8, (byte) 0);
        }
    }

    private static void write(Path file, byte[] pub, byte[] sealed) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.write(FILE_MAGIC);
            out.writeShort(pub.length);
            out.write(pub);
            out.writeInt(sealed.length);
            out.write(sealed);
        }
        Path temp = file.resolveSibling(KEY_FILE + ".tmp");
        Files.write(temp, buffer.toByteArray());
        try {
            Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows: the profile folder's ACL applies; the content is sealed either way
        }
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static byte[] aad(byte[] pub) {
        return transcript("dwb-device-key-v1".getBytes(StandardCharsets.US_ASCII), pub);
    }

    // ------------------------------------------------------------------ identity

    /** {@code hex(SHA-256(public key))}: this device's id on the network. */
    public String fingerprint() {
        return fingerprint;
    }

    /** X.509 encoding of the public key (a copy). */
    public byte[] publicKey() {
        return publicKey.clone();
    }

    /** Set when the previous identity was unusable on this machine and a new one had to be created. */
    public Optional<String> notice() {
        return Optional.ofNullable(notice);
    }

    /** Ed25519 signature over {@link #transcript transcript(parts)}. */
    public byte[] sign(byte[]... parts) {
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initSign(privateKey);
            s.update(transcript(parts));
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }

    // ------------------------------------------------------------------ static helpers

    /** Whether {@code signature} is a valid Ed25519 signature by {@code publicKey} over {@code transcript(parts)}. */
    public static boolean verify(byte[] publicKey, byte[] signature, byte[]... parts) {
        if (signature == null || signature.length != SIGNATURE_BYTES) {
            return false;
        }
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initVerify(publicKey(publicKey));
            s.update(transcript(parts));
            return s.verify(signature);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /** Decodes an X.509 Ed25519 public key; rejects every other key type. */
    public static PublicKey publicKey(byte[] encoded) throws GeneralSecurityException {
        if (encoded == null || encoded.length == 0 || encoded.length > MAX_PUBLIC_KEY_BYTES) {
            throw new java.security.spec.InvalidKeySpecException("bad public key length");
        }
        return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(encoded));
    }

    public static boolean validPublicKey(byte[] encoded) {
        try {
            publicKey(encoded);
            return true;
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    public static String fingerprintOf(byte[] publicKey) {
        return Hashing.hex(Hashing.sha256().digest(publicKey));
    }

    /** {@code fingerprint} grouped for reading aloud and comparing on two screens: {@code 1a2b-3c4d-…} (first 80 bits). */
    public static String display(String fingerprint) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20 && i < fingerprint.length(); i += 4) {
            if (i > 0) {
                sb.append('-');
            }
            sb.append(fingerprint, i, Math.min(i + 4, fingerprint.length()));
        }
        return sb.toString();
    }

    /** Unambiguous encoding of several byte strings: {@code len:u32 | bytes} for each part. */
    public static byte[] transcript(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            int n = part.length;
            out.write(n >>> 24);
            out.write(n >>> 16);
            out.write(n >>> 8);
            out.write(n);
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /** Constant-time comparison of two fingerprints or digests. */
    public static boolean same(byte[] a, byte[] b) {
        return a != null && b != null && MessageDigest.isEqual(a, b);
    }
}
