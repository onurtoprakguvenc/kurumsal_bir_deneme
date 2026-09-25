package org.example.ingest;

import org.example.core.IngestionException;
import org.example.core.IngestionException.Reason;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Defensive checks shared by the Office Open XML extractors: encrypted/legacy container detection, ZIP
 * preflight against bombs, byte budgets for inflated parts and a hardened StAX factory.
 */
final class OoxmlGuards {

    private static final byte[] OLE2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A,
            (byte) 0xE1};
    private static final long RATIO_CHECK_THRESHOLD = 1L << 20;

    private OoxmlGuards() {
    }

    /**
     * Password-protected OOXML files are OLE2 containers with an {@code EncryptedPackage} stream; other OLE2 files
     * are legacy binary formats (.doc/.xls).
     */
    static void rejectOle2(Path file, String format, String legacyName) throws IngestionException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(OLE2.length);
            if (head.length < OLE2.length || !Arrays.equals(head, OLE2)) {
                return;
            }
            byte[] marker = "EncryptedPackage".getBytes(StandardCharsets.UTF_16LE);
            // Scan the first 8 MiB in small blocks; each block keeps the previous block's tail so a marker that
            // straddles a boundary is still found.
            int overlap = marker.length - 1;
            byte[] window = new byte[64 * 1024 + overlap];
            int carried = 0;
            long remaining = 8L * 1024 * 1024;
            while (remaining > 0) {
                int n = in.readNBytes(window, carried, (int) Math.min(window.length - carried, remaining));
                if (n <= 0) {
                    break;
                }
                remaining -= n;
                int filled = carried + n;
                if (indexOf(Arrays.copyOf(window, filled), marker) >= 0) {
                    throw new IngestionException(Reason.ENCRYPTED, format + " is password protected");
                }
                carried = Math.min(overlap, filled);
                System.arraycopy(window, filled - carried, window, 0, carried);
            }
            throw new IngestionException(Reason.UNSUPPORTED,
                    "Legacy binary " + legacyName + " is not supported; save as ." + format.toLowerCase(java.util.Locale.ROOT));
        } catch (IOException e) {
            throw new IngestionException(Reason.IO_ERROR, "Cannot read " + format + ": " + e.getMessage(), e);
        }
    }

    /**
     * Reads only the ZIP central directory and rejects packages whose declared sizes, entry count or compression
     * ratios exceed the limits.
     */
    static void preflight(Path file, Limits limits, String format) throws IngestionException {
        int entryCount = 0;
        long total = 0;
        try (ZipFile zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++entryCount > limits.maxZipEntries()) {
                    throw new IngestionException(Reason.TOO_LARGE, format + " package has more than "
                            + limits.maxZipEntries() + " entries");
                }
                long size = entry.getSize();
                long compressed = entry.getCompressedSize();
                if (size > limits.maxPartBytes()) {
                    throw new IngestionException(Reason.TOO_LARGE, "Part " + entry.getName() + " declares "
                            + (size >> 20) + " MB (limit " + (limits.maxPartBytes() >> 20) + " MB)");
                }
                if (size > RATIO_CHECK_THRESHOLD && compressed > 0 && size / compressed > limits.maxInflateRatio()) {
                    throw new IngestionException(Reason.TOO_LARGE, "Part " + entry.getName()
                            + " has a compression ratio of " + (size / compressed) + ":1 (possible ZIP bomb)");
                }
                total += Math.max(0, size);
                if (total > limits.maxUncompressedBytes()) {
                    throw new IngestionException(Reason.TOO_LARGE, format + " package inflates to more than "
                            + (limits.maxUncompressedBytes() >> 20) + " MB");
                }
            }
        } catch (ZipException e) {
            throw new IngestionException(Reason.CORRUPTED, "Corrupted " + format + " archive: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IngestionException(Reason.IO_ERROR, "Cannot read " + format + ": " + e.getMessage(), e);
        }
    }

    /** Thrown when an inflated part exceeds its byte budget (declared sizes can lie). */
    static final class BudgetExceededException extends IOException {
        private static final long serialVersionUID = 1L;
        static final String MESSAGE = "decompressed size budget exceeded";

        BudgetExceededException() {
            super(MESSAGE);
        }
    }

    /**
     * Wraps a part stream so that at most {@code partLimit} bytes are read from it and the shared
     * {@code totalBudget[0]} counter never goes negative.
     */
    static InputStream bounded(InputStream in, long partLimit, long[] totalBudget) {
        return new FilterInputStream(in) {
            private long consumed;

            @Override
            public int read() throws IOException {
                int b = super.read();
                if (b >= 0) {
                    consume(1);
                }
                return b;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int n = super.read(buffer, offset, length);
                if (n > 0) {
                    consume(n);
                }
                return n;
            }

            private void consume(int n) throws BudgetExceededException {
                consumed += n;
                totalBudget[0] -= n;
                if (consumed > partLimit || totalBudget[0] < 0) {
                    throw new BudgetExceededException();
                }
            }
        };
    }

    /** True when {@code error} or any cause signals an exceeded byte budget. */
    static boolean budgetExceeded(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof BudgetExceededException
                    || (t.getMessage() != null && t.getMessage().contains(BudgetExceededException.MESSAGE))) {
                return true;
            }
            if (t instanceof XMLStreamException xse && xse.getNestedException() instanceof BudgetExceededException) {
                return true;
            }
        }
        return false;
    }

    /**
     * StAX factory hardened against XXE and entity expansion: no DTD support, no external entities. Created per
     * part because {@link XMLInputFactory} instances are not guaranteed to be thread-safe.
     */
    static XMLInputFactory xmlInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLInputFactory.IS_COALESCING, false);
        return factory;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
