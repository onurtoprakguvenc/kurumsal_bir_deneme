package org.yazi.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutputSanitizerTest {

    // --- prose must survive (the old StreamSanitizer ate these) ----------------------------------

    @Test
    void keepsProseStartingWithWait() {
        assertEquals("Wait, she said.", OutputSanitizer.clean("Wait, she said."));
        assertEquals("\"Wait,\" she said.", OutputSanitizer.clean("\"Wait,\" she said."));
    }

    @Test
    void keepsProseStartingWithThinking() {
        assertEquals("Thinking of her, he ran.", OutputSanitizer.clean("Thinking of her, he ran."));
    }

    @Test
    void keepsWritersOwnParentheticalStageDirection() {
        String text = "(slices a bit of her face)\n\"you are a monster.\"";
        assertEquals(text, OutputSanitizer.clean(text));
    }

    @Test
    void keepsLeadingParagraphBreak() {
        assertEquals("\n\nShe ran.", OutputSanitizer.clean("\n\nShe ran.  \n"));
    }

    // --- artefacts must go ------------------------------------------------------------------------

    @Test
    void removesBracketedReasoningAside() {
        assertEquals("She ran.", OutputSanitizer.clean("(Wait, if I append this the directive says one line.)\nShe ran."));
    }

    @Test
    void removesReasoningLine() {
        assertEquals("She ran.", OutputSanitizer.clean("Let me think about the pacing.\n\nShe ran."));
        assertEquals("She ran.", OutputSanitizer.clean("The directive is to continue.\nShe ran."));
    }

    @Test
    void removesCodeFence() {
        assertEquals("She ran.", OutputSanitizer.clean("```text\nShe ran.\n```"));
        assertEquals("She ran.", OutputSanitizer.clean("```\nShe ran.```"));
    }

    @Test
    void removesEchoedPromptCue() {
        assertEquals("She ran.", OutputSanitizer.clean("CONTINUATION: She ran."));
        assertEquals("She ran.", OutputSanitizer.clean("REPLACEMENT:\nShe ran."));
    }

    @Test
    void removesStackedArtefacts() {
        assertEquals("She ran.", OutputSanitizer.clean("```\nCONTINUATION:\nLet me think.\nShe ran.\n```"));
    }

    @Test
    void lowercaseCueWordIsProse() {
        assertEquals("output: nothing.", OutputSanitizer.clean("output: nothing."));
    }
}
