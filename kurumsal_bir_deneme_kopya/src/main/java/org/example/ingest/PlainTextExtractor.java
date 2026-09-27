package org.example.ingest;

import org.example.core.IngestionException;
import org.example.core.IngestionException.Reason;

import java.io.IOException;
import java.io.Reader;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Path;

/**
 * Streams plain text in 8K-char blocks using {@link TextEncoding} detection with a strict-UTF-8-then-fallback
 * decode.
 */
final class PlainTextExtractor {

    private PlainTextExtractor() {
    }

    static void extract(Path file, TextChunker sink) throws IngestionException {
        TextEncoding.Detected detected = TextEncoding.sniff(file);
        try {
            decode(file, detected, detected.charset(), CodingErrorAction.REPORT, sink);
        } catch (CharacterCodingException e) {
            sink.reset();
            try {
                decode(file, detected, detected.retryCharset(), CodingErrorAction.REPLACE, sink);
            } catch (IOException e2) {
                throw new IngestionException(Reason.IO_ERROR, "Cannot read text file: " + e2.getMessage(), e2);
            }
        } catch (IOException e) {
            throw new IngestionException(Reason.IO_ERROR, "Cannot read text file: " + e.getMessage(), e);
        }
    }

    private static void decode(Path file, TextEncoding.Detected detected, Charset charset, CodingErrorAction onError,
                               TextChunker sink) throws IOException {
        try (Reader reader = TextEncoding.open(file, detected, charset, onError)) {
            char[] buffer = new char[8_192];
            int n;
            while ((n = reader.read(buffer)) >= 0 && !sink.truncated()) {
                sink.append(CharBuffer.wrap(buffer, 0, n));
            }
        }
    }
}
