package org.yazi.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BufferSnapshotTest {

    @Test
    void clampsOutOfRangeOffsets() {
        BufferSnapshot s = new BufferSnapshot(1, "hello", new Span(2, 99), 99);
        assertEquals(5, s.caret());
        assertEquals(new Span(2, 5), s.selection());
        assertEquals("llo", s.selectedText());
    }

    @Test
    void nullTextBecomesEmpty() {
        BufferSnapshot s = BufferSnapshot.insertion(0, null, 10);
        assertEquals("", s.text());
        assertEquals(0, s.caret());
        assertFalse(s.hasSelection());
    }

    @Test
    void selectionFactoryNormalisesOrderAndPutsCaretAtEnd() {
        BufferSnapshot s = BufferSnapshot.selection(3, "abcdef", 4, 1);
        assertEquals(new Span(1, 4), s.selection());
        assertEquals(4, s.caret());
        assertEquals("bcd", s.selectedText());
    }

    @Test
    void spanRejectsInvalidRanges() {
        assertThrows(IllegalArgumentException.class, () -> new Span(3, 1));
        assertThrows(IllegalArgumentException.class, () -> new Span(-1, 1));
    }
}
