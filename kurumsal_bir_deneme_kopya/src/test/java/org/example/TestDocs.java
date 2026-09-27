package org.example;

import org.example.model.DocumentRecord;
import org.example.model.DocumentType;
import org.example.model.TextChunk;
import org.example.util.Hashing;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** In-memory documents for index and storage tests: one chunk per text, contiguous offsets, content-derived hash. */
public final class TestDocs {

    private TestDocs() {
    }

    public static DocumentRecord doc(String name, String... chunkTexts) {
        List<TextChunk> chunks = new ArrayList<>();
        long offset = 0;
        for (int i = 0; i < chunkTexts.length; i++) {
            chunks.add(new TextChunk(i, -1, offset, chunkTexts[i]));
            offset += chunkTexts[i].length();
        }
        String sha = Hashing.hex(Hashing.sha256().digest((name + "\u0000" + String.join("\u0001", chunkTexts))
                .getBytes(StandardCharsets.UTF_8)));
        return new DocumentRecord(sha, name, Path.of(name), DocumentType.TXT, offset, -1, 0, offset,
                Instant.EPOCH, DocumentRecord.LOCAL, chunks);
    }
}
