package org.example.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * SHA-256 content addressing helpers. Files are hashed in fixed-size blocks, never loaded whole.
 */
public final class Hashing {

    public static final int SHA256_BYTES = 32;
    private static final int BLOCK = 64 * 1024;
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
    private static final HexFormat HEX = HexFormat.of();

    private Hashing() {
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every Java runtime", e);
        }
    }

    public static String sha256Hex(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream in = Files.newInputStream(file)) {
            update(digest, in, Long.MAX_VALUE);
        }
        return HEX.formatHex(digest.digest());
    }

    /**
     * Streams the file through SHA-256 in fixed blocks, reporting the running byte count after each block (for
     * multi-gigabyte media, where hashing alone takes a while). {@code progress} must be cheap and non-blocking.
     */
    public static String sha256Hex(Path file, java.util.function.LongConsumer progress) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[BLOCK];
        long total = 0;
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, n);
                total += n;
                progress.accept(total);
            }
        }
        return HEX.formatHex(digest.digest());
    }

    /** Feeds at most {@code limit} bytes of {@code in} into {@code digest}; returns the number consumed. */
    public static long update(MessageDigest digest, InputStream in, long limit) throws IOException {
        byte[] buffer = new byte[BLOCK];
        long total = 0;
        while (total < limit) {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, limit - total));
            if (n < 0) {
                break;
            }
            digest.update(buffer, 0, n);
            total += n;
        }
        return total;
    }

    public static String hex(byte[] bytes) {
        return HEX.formatHex(bytes);
    }

    public static byte[] fromHex(String hex) {
        return HEX.parseHex(hex);
    }

    public static boolean isSha256Hex(String value) {
        return value != null && SHA256_HEX.matcher(value).matches();
    }
}
