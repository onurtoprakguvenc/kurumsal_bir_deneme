package org.yazi.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SeamStitcherTest {

    // --- bugs carried over from DiffMergeEngine -------------------------------------------------

    @Test
    void noSpaceAfterOpeningStraightQuote() {
        assertEquals("Hello", SeamStitcher.stitch("He said \"", "Hello", ""));
    }

    @Test
    void noSpaceAfterOpeningBracketOrCurlyQuote() {
        assertEquals("aside", SeamStitcher.stitch("text (", "aside", ""));
        assertEquals("Merhaba", SeamStitcher.stitch("Dedi ki “", "Merhaba", ""));
    }

    @Test
    void turkishApostropheSuffixStaysAttached() {
        assertEquals("da kaldı.", SeamStitcher.stitch("Ankara'", "da kaldı.", ""));
        assertEquals("da", SeamStitcher.stitch("Ankara’", "da", ""));
    }

    @Test
    void closingStraightQuoteKeepsModelsNewQuote() {
        // The buffer's quote is a closing one, so the model's opening quote is a new line of dialogue.
        assertEquals(" \"Run.\"", SeamStitcher.stitch("\"Stop.\"", "\"Run.\"", ""));
    }

    @Test
    void duplicatedOpeningQuoteIsDropped() {
        assertEquals("Hello", SeamStitcher.stitch("He said \"", "\"Hello", ""));
    }

    // --- ordinary seams ---------------------------------------------------------------------------

    @Test
    void separatesSentences() {
        assertEquals(" Then he left.", SeamStitcher.stitch("She ran.", "Then he left.", ""));
    }

    @Test
    void separatesWordsAfterClosingQuote() {
        assertEquals(" He left.", SeamStitcher.stitch("\"Hi.\"", "He left.", ""));
    }

    @Test
    void punctuationAttachesToPreviousWord() {
        assertEquals(", then ran", SeamStitcher.stitch("He paused", ", then ran", ""));
    }

    @Test
    void noDoubleSpaceAtLeadingSeam() {
        assertEquals("next", SeamStitcher.stitch("word ", "  next", ""));
    }

    @Test
    void keepsModelsParagraphBreak() {
        assertEquals("\n\nNew paragraph.", SeamStitcher.stitch("End.", "\n\nNew paragraph.", ""));
    }

    @Test
    void completingAWordInsideAWordAddsNoSpace() {
        assertEquals("believ", SeamStitcher.stitch("un", "believ", "able"));
    }

    @Test
    void repeatedDashIsDropped() {
        assertEquals("then ran", SeamStitcher.stitch("He paused—", "—then ran", ""));
    }

    @Test
    void documentStartHasNoLeadingWhitespace() {
        assertEquals("Hello", SeamStitcher.stitch("", "  \nHello", ""));
    }

    // --- writer's own style ----------------------------------------------------------------------

    @Test
    void respectsWriterWhoGluesSentences() {
        String before = "\"i know.who denies that.know,back to your back.\"\nshe stood.her knee cracked.";
        assertEquals("she kicked.", SeamStitcher.stitch(before, "she kicked.", ""));
    }

    // --- trailing seam ----------------------------------------------------------------------------

    @Test
    void separatesInsertedSentenceFromFollowingWord() {
        assertEquals(" B. ", SeamStitcher.stitch("A.", "B.", "C"));
    }

    @Test
    void noDoubleSpaceAtTrailingSeam() {
        assertEquals("B", SeamStitcher.stitch("A ", "B  ", " C"));
    }

    @Test
    void duplicatedClosingQuoteIsDropped() {
        assertEquals("Go", SeamStitcher.stitch("", "Go\"", "\" he said"));
    }

    @Test
    void nullsAreTreatedAsEmpty() {
        assertEquals("", SeamStitcher.stitch(null, null, null));
        assertEquals("x", SeamStitcher.stitch(null, "x", null));
    }
}
