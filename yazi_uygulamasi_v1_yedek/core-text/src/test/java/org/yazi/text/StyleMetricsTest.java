package org.yazi.text;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StyleMetricsTest {

    /** The default sample from writing_improve_v2's Main. */
    private static final String AUTHOR_SAMPLE = """
            (intense fight moment)
            "so sister.how does it feel to...(slices a bit of her face and drops blood)"
            "you are a mo-mo-monster."
            "i know.who denies that.know,back to your back."
            """;

    @Test
    void recognisesAuthorsLowercaseGluedStyle() {
        StyleProfile p = StyleMetrics.measure(AUTHOR_SAMPLE);
        assertTrue(p.isMeasured());
        assertTrue(p.lowercaseSentenceStarts());
        assertTrue(p.gluedPunctuation());
        assertTrue(p.inlineParentheticals());
    }

    @Test
    void conventionalProseIsNotFlagged() {
        StyleProfile p = StyleMetrics.measure("The rain stopped. She opened the door and stepped outside. Nobody was there.");
        assertEquals(3, p.sentenceCount());
        assertFalse(p.lowercaseSentenceStarts());
        assertFalse(p.gluedPunctuation());
        assertFalse(p.lineDelimited());
    }

    @Test
    void doesNotSplitInsideNumbersOrBeforeClosingQuote() {
        assertEquals(List.of("It cost 3.5 lira.", "\"Fine.\"", "He paid."),
                StyleMetrics.sentences("It cost 3.5 lira. \"Fine.\" He paid."));
    }

    @Test
    void detectsLineDelimitedDialogue() {
        String script = "\"Where?\"\n\"Here.\"\n\"Now?\"\n- yes";
        assertTrue(StyleMetrics.measure(script).lineDelimited());
    }

    @Test
    void emptyTextIsNeutral() {
        assertSame(StyleProfile.NEUTRAL, StyleMetrics.measure("   "));
        assertSame(StyleProfile.NEUTRAL, StyleMetrics.measure(null));
    }
}
