package org.example.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnitsAndHashingTest {

    @TempDir
    Path dir;

    @Test
    void rates() {
        assertEquals("0 B/s", Units.rate(0));
        assertEquals("0 B/s", Units.rate(-5));
        assertEquals("0 B/s", Units.rate(Double.NaN));
        assertEquals("512 B/s", Units.rate(512));
        assertEquals("500 KB/s", Units.rate(500 * 1024));
        assertEquals("84.1 MB/s", Units.rate(84.1 * 1024 * 1024));
        assertEquals("1.50 GB/s", Units.rate(1.5 * 1024 * 1024 * 1024));
    }

    @Test
    void remainingTimesInTurkish() {
        assertNull(Units.remainingTr(-1));
        assertEquals("0 sn", Units.remainingTr(0));
        assertEquals("59 sn", Units.remainingTr(59));
        assertEquals("1 dk 00 sn", Units.remainingTr(60));
        assertEquals("3 dk 04 sn", Units.remainingTr(184));
        assertEquals("1 sa 02 dk", Units.remainingTr(3_720));
        assertEquals("2 gün 3 sa", Units.remainingTr(2 * 86_400 + 3 * 3_600 + 59));
    }

    @Test
    void streamingHashEqualsOneShotDigestAndReportsProgress() throws IOException {
        byte[] data = new byte[3 * 64 * 1024 + 123];
        new Random(7).nextBytes(data);
        Path file = Files.write(dir.resolve("blob.bin"), data);
        String expected = Hashing.hex(Hashing.sha256().digest(data));
        assertEquals(expected, Hashing.sha256Hex(file));
        List<Long> progress = new ArrayList<>();
        assertEquals(expected, Hashing.sha256Hex(file, progress::add));
        assertEquals(data.length, progress.getLast());
        assertTrue(progress.size() >= 4, "reported per 64 KiB block");
        for (int i = 1; i < progress.size(); i++) {
            assertTrue(progress.get(i) > progress.get(i - 1));
        }
    }

    @Test
    void boundedUpdateConsumesAtMostTheLimit() throws IOException {
        byte[] data = "0123456789".getBytes(StandardCharsets.US_ASCII);
        MessageDigest digest = Hashing.sha256();
        assertEquals(4, Hashing.update(digest, new ByteArrayInputStream(data), 4));
        assertEquals(Hashing.hex(Hashing.sha256().digest("0123".getBytes(StandardCharsets.US_ASCII))),
                Hashing.hex(digest.digest()));
    }

    @Test
    void hexValidation() {
        String sha = "a".repeat(64);
        assertTrue(Hashing.isSha256Hex(sha));
        assertFalse(Hashing.isSha256Hex(sha.toUpperCase()));
        assertFalse(Hashing.isSha256Hex("a".repeat(63)));
        assertFalse(Hashing.isSha256Hex(null));
        assertEquals(sha, Hashing.hex(Hashing.fromHex(sha)));
    }
}
