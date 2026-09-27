package org.yazi.desktop;

import org.junit.jupiter.api.Test;
import org.yazi.model.Span;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TextFinderTest {

    private static final String TEXT = "The cat sat. THE end. the";

    @Test
    void findsAllOccurrencesIgnoringCaseByDefault() {
        assertEquals(List.of(new Span(0, 3), new Span(13, 16), new Span(22, 25)),
                TextFinder.findAll(TEXT, "the", false));
    }

    @Test
    void matchCaseIsExact() {
        assertEquals(List.of(new Span(22, 25)), TextFinder.findAll(TEXT, "the", true));
    }

    @Test
    void matchesDoNotOverlap() {
        assertEquals(List.of(new Span(0, 2), new Span(2, 4)), TextFinder.findAll("aaaaa", "aa", true));
    }

    @Test
    void emptyOrOversizedQueriesFindNothing() {
        assertTrue(TextFinder.findAll(TEXT, "", false).isEmpty());
        assertTrue(TextFinder.findAll("ab", "abc", false).isEmpty());
        assertTrue(TextFinder.findAll(null, "a", false).isEmpty());
        assertTrue(TextFinder.findAll("", "a", false).isEmpty());
    }

    @Test
    void turkishCaseFoldingKeepsOffsetsExact() {
        // "İ".toLowerCase() is two chars ("i̇"); lower-casing the whole text would shift every later match.
        String text = "İSTANBUL ve istanbul, İzmir";
        List<Span> matches = TextFinder.findAll(text, "istanbul", false);
        assertEquals(List.of(new Span(0, 8), new Span(12, 20)), matches);
        for (Span m : matches) {
            assertTrue(text.substring(m.start(), m.end()).equalsIgnoreCase("istanbul"));
        }
        assertEquals(List.of(new Span(22, 27)), TextFinder.findAll(text, "izmir", false));
    }

    @Test
    void findsTextNextToSurrogatePairs() {
        String text = "🌙 gece 🌙 gece";
        assertEquals(List.of(new Span(3, 7), new Span(11, 15)), TextFinder.findAll(text, "gece", false));
    }

    @Test
    void nextAndPreviousWrapAround() {
        List<Span> m = TextFinder.findAll(TEXT, "the", false);
        assertEquals(1, TextFinder.nextIndex(m, 3));    // after the first match
        assertEquals(0, TextFinder.nextIndex(m, 23));   // past the last: wrap to first
        assertEquals(0, TextFinder.nextIndex(m, 0));    // a match starting at the caret counts
        assertEquals(1, TextFinder.previousIndex(m, 22));
        assertEquals(2, TextFinder.previousIndex(m, 0)); // before the first: wrap to last
        assertEquals(-1, TextFinder.nextIndex(List.of(), 0));
        assertEquals(-1, TextFinder.previousIndex(List.of(), 0));
    }

    @Test
    void counterText() {
        List<Span> m = TextFinder.findAll(TEXT, "the", false);
        assertEquals("", TextFinder.counter(List.of(), -1, true));
        assertEquals("No matches", TextFinder.counter(List.of(), -1, false));
        assertEquals("2 of 3", TextFinder.counter(m, 1, false));
        assertEquals("3 matches", TextFinder.counter(m, -1, false));
        assertEquals("1 match", TextFinder.counter(m.subList(0, 1), -1, false));
        assertEquals(1, TextFinder.indexOf(m, new Span(13, 16)));
        assertEquals(-1, TextFinder.indexOf(m, new Span(13, 15)));
    }

    @Test
    void hugeMatchCountsAreCappedAndShownWithAPlus() {
        String text = "a".repeat(TextFinder.MAX_MATCHES + 500);
        List<Span> m = TextFinder.findAll(text, "a", true);
        assertEquals(TextFinder.MAX_MATCHES, m.size());
        String cap = String.format("%,d", TextFinder.MAX_MATCHES);   // "10,000" or "10.000", per locale
        assertEquals(cap + "+ matches", TextFinder.counter(m, -1, false));
        assertEquals("1 of " + cap + "+", TextFinder.counter(m, 0, false));
    }
}
