package org.example.ingest;

import org.example.core.IngestionException;
import org.example.core.IngestionException.Reason;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Encoding detection shared by the text-based extractors (TXT, CSV/TSV).
 *
 * <p>Order: byte-order mark, UTF-16 without BOM (NUL-byte distribution), otherwise strict UTF-8. Callers decode
 * with {@link CodingErrorAction#REPORT} first and restart with {@link #FALLBACK} (windows-1254, the Turkish
 * ANSI code page and a superset of Latin-1's printable range) when the bytes are not valid UTF-8.</p>
 */
final class TextEncoding {

    static final Charset FALLBACK = Charset.forName("windows-1254");
    private static final int SAMPLE_BYTES = 8_192;

    /** Detected charset and the number of BOM bytes to skip. */
    record Detected(Charset charset, int bomLength) {

        /** Charset for the second attempt after a strict decode failed. */
        Charset retryCharset() {
            return charset == StandardCharsets.UTF_8 ? FALLBACK : charset;
        }
    }

    private TextEncoding() {
    }

    static Detected sniff(Path file) throws IngestionException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(SAMPLE_BYTES);
        } catch (IOException e) {
            throw new IngestionException(Reason.IO_ERROR, "Cannot read " + file.getFileName() + ": " + e.getMessage(), e);
        }
        if (head.length >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) {
            return new Detected(StandardCharsets.UTF_8, 3);
        }
        if (head.length >= 2 && (head[0] & 0xFF) == 0xFE && (head[1] & 0xFF) == 0xFF) {
            return new Detected(StandardCharsets.UTF_16BE, 2);
        }
        if (head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFE) {
            return new Detected(StandardCharsets.UTF_16LE, 2);
        }
        int evenZero = 0;
        int oddZero = 0;
        for (int i = 0; i < head.length; i++) {
            if (head[i] == 0) {
                if ((i & 1) == 0) {
                    evenZero++;
                } else {
                    oddZero++;
                }
            }
        }
        int pairs = Math.max(1, head.length / 2);
        // Latin text in UTF-16 has a NUL in almost every pair; non-Latin text (Cyrillic, Greek, CJK…) only for spaces
        // and ASCII punctuation, so a smaller share is accepted when the NULs sit exclusively on one parity.
        if ((oddZero > pairs * 0.3 && evenZero < pairs * 0.05) || (evenZero == 0 && oddZero >= pairs * 0.05)) {
            return new Detected(StandardCharsets.UTF_16LE, 0);
        }
        if ((evenZero > pairs * 0.3 && oddZero < pairs * 0.05) || (oddZero == 0 && evenZero >= pairs * 0.05)) {
            return new Detected(StandardCharsets.UTF_16BE, 0);
        }
        if (evenZero + oddZero > 0) {
            throw new IngestionException(Reason.UNSUPPORTED, "File contains binary data, not text");
        }
        return new Detected(StandardCharsets.UTF_8, 0);
    }

    /** Opens a decoding reader positioned after the BOM. The caller closes it. */
    static Reader open(Path file, Detected detected, Charset charset, CodingErrorAction onError) throws IOException {
        InputStream raw = new BufferedInputStream(Files.newInputStream(file), 64 * 1024);
        try {
            raw.skipNBytes(detected.bomLength());
            return new InputStreamReader(raw, charset.newDecoder()
                    .onMalformedInput(onError)
                    .onUnmappableCharacter(onError));
        } catch (IOException | RuntimeException e) {
            raw.close();
            throw e;
        }
    }
}
