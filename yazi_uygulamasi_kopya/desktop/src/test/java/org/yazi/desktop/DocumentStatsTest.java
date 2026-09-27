package org.yazi.desktop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DocumentStatsTest {

    @Test
    void emptyAndBlankTexts() {
        assertEquals(new DocumentStats(0, 0), DocumentStats.of(""));
        assertEquals(new DocumentStats(0, 0), DocumentStats.of(null));
        assertEquals(new DocumentStats(0, 3), DocumentStats.of(" \n\t"));
        assertEquals("0 words · 0 characters", DocumentStats.of("").describe());
    }

    @Test
    void contractionsHyphensAndTurkishSuffixesAreOneWord() {
        assertEquals(4, DocumentStats.of("don't e-posta Ankara'ya 3.5").words());
    }

    @Test
    void punctuationAloneIsNotAWord() {
        assertEquals(2, DocumentStats.of("Evet — hayır \" …").words());
    }

    @Test
    void wordsSeparatedByAnyWhitespaceIncludingNoBreakSpace() {
        assertEquals(4, DocumentStats.of("bir\niki\tüç dört").words());
    }

    @Test
    void charactersAreCodePointsSoEmojiCountOnce() {
        DocumentStats s = DocumentStats.of("ay 🌙");
        assertEquals(4, s.characters());
        assertEquals(1, s.words(), "an emoji alone is not a word");
    }

    @Test
    void readingTimeRoundsUp() {
        assertEquals(0, new DocumentStats(0, 0).readingMinutes());
        assertEquals(1, new DocumentStats(1, 3).readingMinutes());
        assertEquals(1, new DocumentStats(230, 1000).readingMinutes());
        assertEquals(2, new DocumentStats(231, 1000).readingMinutes());
    }

    @Test
    void descriptions() {
        assertEquals("1 word · 5 characters · 1 min read", DocumentStats.of("hello").describe());
        // Grouping follows the default locale, like the cost label ("1.234" on a Turkish system).
        assertEquals(n(1234) + " words · " + n(7012) + " characters · 6 min read",
                new DocumentStats(1234, 7012).describe());
        assertEquals("2 of " + n(1234) + " words selected · 9 characters",
                DocumentStats.of("two words").describeSelection(new DocumentStats(1234, 7012)));
    }

    private static String n(int value) {
        return String.format("%,d", value);
    }
}
