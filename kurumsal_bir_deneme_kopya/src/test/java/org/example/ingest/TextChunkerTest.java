package org.example.ingest;

import org.example.model.TextChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The streaming normalizer/chunker: normalization rules, cut points, pages, offsets and limits. */
class TextChunkerTest {

    private static String joined(List<TextChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        chunks.forEach(c -> sb.append(c.text()));
        return sb.toString();
    }

    @Test
    void normalizesWhitespaceLineBreaksAndInvisibles() {
        TextChunker chunker = new TextChunker(200, 1_000_000);
        chunker.append("  Merhaba\t\tdünya\r\n\r\n\r\n\r\nyeni­paragraf​  son kelime\u0007");
        List<TextChunk> chunks = chunker.finish();
        assertEquals(1, chunks.size());
        assertEquals("Merhaba dünya\n\nyeniparagraf son kelime", chunks.getFirst().text());
    }

    @Test
    void chunksConcatenateToTheDocumentAndOffsetsAreExact() {
        TextChunker chunker = new TextChunker(200, 10_000_000);
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            String sentence = "Cümle numarası " + i + " burada bitiyor. ";
            chunker.append(sentence);
            expected.append(sentence);
        }
        List<TextChunk> chunks = chunker.finish();
        assertTrue(chunks.size() > 10);
        assertEquals(expected.toString().strip(), joined(chunks).strip());
        long offset = 0;
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk c = chunks.get(i);
            assertEquals(i, c.index());
            assertEquals(offset, c.startOffset());
            assertTrue(c.text().length() <= 300, "never above the hard maximum (1.5 × target)");
            offset = c.endOffset();
        }
    }

    @Test
    void cutsAtSentenceOrSpaceNeverInsideAWord() {
        TextChunker chunker = new TextChunker(200, 10_000_000);
        for (int i = 0; i < 200; i++) {
            chunker.append("kelime" + i + " ");
        }
        for (TextChunk c : chunker.finish()) {
            char last = c.text().charAt(c.text().length() - 1);
            assertTrue(last == ' ' || Character.isDigit(last), "chunk ends at a word boundary: '" + c.text() + "'");
        }
    }

    @Test
    void pagesNeverShareAChunk() {
        TextChunker chunker = new TextChunker(200, 1_000_000);
        chunker.startPage(1);
        chunker.append("birinci sayfa");
        chunker.startPage(2);
        chunker.append("ikinci sayfa");
        List<TextChunk> chunks = chunker.finish();
        assertEquals(2, chunks.size());
        assertEquals(1, chunks.get(0).page());
        assertEquals(2, chunks.get(1).page());
        assertTrue(chunks.get(0).text().endsWith("\n\n"));
    }

    @Test
    void preformattedTextKeepsPaddingAndBreaks() {
        TextChunker chunker = new TextChunker(200, 1_000_000);
        chunker.appendPreformatted("| A   | B |\n|-----|---|\n| 1   | 2 |\n");
        chunker.endChunk();
        chunker.append("sonrası");
        List<TextChunk> chunks = chunker.finish();
        assertEquals(2, chunks.size());
        assertEquals("| A   | B |\n|-----|---|\n| 1   | 2 |\n", chunks.getFirst().text());
    }

    @Test
    void characterLimitTruncatesAndIsNotedOnce() {
        TextChunker chunker = new TextChunker(200, 1_000);
        for (int i = 0; i < 1_000; i++) {
            chunker.append("abcdefghij ");
        }
        assertTrue(chunker.truncated());
        assertFalse(chunker.memoryTruncated());
        chunker.noteCharacterLimit();
        chunker.noteCharacterLimit();
        String text = joined(chunker.finish());
        assertEquals(1, text.split("metin sınırına ulaştı", -1).length - 1, "the note appears exactly once");
        assertEquals(0, chunker.remainingChars());
    }

    @Test
    void resetDiscardsEverything() {
        TextChunker chunker = new TextChunker(200, 1_000_000);
        chunker.append("eski metin");
        chunker.reset();
        chunker.append("yeni");
        List<TextChunk> chunks = chunker.finish();
        assertEquals(1, chunks.size());
        assertEquals("yeni", chunks.getFirst().text());
        assertEquals(0, chunks.getFirst().startOffset());
    }

    @Test
    void blankInputProducesNoChunksAndTinyTargetsAreRejected() {
        TextChunker chunker = new TextChunker(200, 1_000_000);
        chunker.append(" \t\r\n ​ ");
        assertTrue(chunker.finish().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new TextChunker(199, 1_000));
    }
}
