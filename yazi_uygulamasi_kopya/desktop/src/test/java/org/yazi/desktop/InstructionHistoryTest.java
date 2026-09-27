package org.yazi.desktop;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InstructionHistoryTest {

    @Test
    void emptyHistoryLeavesTheLineAlone() {
        InstructionHistory h = new InstructionHistory();
        assertEquals("typing", h.previous("typing"));
        assertEquals("typing", h.next("typing"));
    }

    @Test
    void browsesBackAndForwardAndRestoresTheDraft() {
        InstructionHistory h = new InstructionHistory();
        h.record("shorter");
        h.record("more formal");

        assertEquals("more formal", h.previous("half-typ"));
        assertEquals("shorter", h.previous("more formal"));
        assertEquals("shorter", h.previous("shorter"), "stops at the oldest");
        assertEquals("more formal", h.next("shorter"));
        assertEquals("half-typ", h.next("more formal"), "the draft comes back");
        assertEquals("half-typ", h.next("half-typ"), "nothing newer than the draft");
    }

    @Test
    void blanksAreIgnoredAndRepeatsMoveToTheEnd() {
        InstructionHistory h = new InstructionHistory();
        h.record("a");
        h.record("  ");
        h.record(null);
        h.record("b");
        h.record(" a ");
        assertEquals(List.of("b", "a"), h.entries());
    }

    @Test
    void recordingResetsTheBrowsePosition() {
        InstructionHistory h = new InstructionHistory();
        h.record("one");
        h.record("two");
        h.previous("");
        h.previous("");
        h.record("three");
        assertEquals("three", h.previous(""));
    }

    @Test
    void keepsOnlyTheNewestEntries() {
        InstructionHistory h = new InstructionHistory();
        for (int i = 0; i < InstructionHistory.MAX_ENTRIES + 10; i++) {
            h.record("instruction " + i);
        }
        List<String> entries = h.entries();
        assertEquals(InstructionHistory.MAX_ENTRIES, entries.size());
        assertEquals("instruction 10", entries.getFirst());
        assertEquals("instruction " + (InstructionHistory.MAX_ENTRIES + 9), entries.getLast());
    }
}
