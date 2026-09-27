package org.yazi.text;

import org.junit.jupiter.api.Test;
import org.yazi.model.BufferSnapshot;
import org.yazi.model.Span;

import static org.junit.jupiter.api.Assertions.*;

class SpliceEngineTest {

    @Test
    void insertionMovesCaretToEndOfPayload() {
        SpliceEngine.SpliceResult r = SpliceEngine.splice("She ran.", Span.caret(8), "Then he left.");
        assertEquals("She ran. Then he left.", r.text());
        assertEquals(22, r.caret());
        assertEquals(new Span(8, 22), r.inserted());
    }

    @Test
    void replacementKeepsSurroundingText() {
        SpliceEngine.SpliceResult r = SpliceEngine.splice("The old dog.", new Span(4, 7), "tired");
        assertEquals("The tired dog.", r.text());
        assertEquals(9, r.caret());
    }

    @Test
    void outOfRangeTargetIsClamped() {
        SpliceEngine.SpliceResult r = SpliceEngine.splice("abc", new Span(10, 20), "d");
        assertEquals("abc d", r.text());
    }

    @Test
    void refusesStaleSnapshot() {
        BufferSnapshot snapshot = BufferSnapshot.insertion(4, "text", 4);
        StaleBufferException e = assertThrows(StaleBufferException.class,
                () -> SpliceEngine.splice(snapshot, 5, Span.caret(4), " more"));
        assertEquals(4, e.expectedRevision());
        assertEquals(5, e.actualRevision());
    }

    @Test
    void splicesMatchingSnapshot() throws StaleBufferException {
        BufferSnapshot snapshot = BufferSnapshot.insertion(4, "One.", 4);
        assertEquals("One. Two.", SpliceEngine.splice(snapshot, 4, Span.caret(4), "Two.").text());
    }
}
