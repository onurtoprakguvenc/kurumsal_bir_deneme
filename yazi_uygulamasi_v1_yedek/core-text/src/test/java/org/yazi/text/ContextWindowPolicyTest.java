package org.yazi.text;

import org.junit.jupiter.api.Test;
import org.yazi.model.BufferSnapshot;
import org.yazi.model.ContextWindow;
import org.yazi.model.Span;
import org.yazi.model.Tier;

import static org.junit.jupiter.api.Assertions.*;

class ContextWindowPolicyTest {

    private static String manuscript(int paragraphs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paragraphs; i++) {
            sb.append("Paragraph ").append(i).append(" begins here. It has a few plain sentences in it. ")
                    .append("The rain kept falling on the stone steps outside.\n\n");
        }
        return sb.toString();
    }

    @Test
    void continuationCostDoesNotGrowWithDocumentLength() {
        for (int paragraphs : new int[]{100, 1_000, 5_000}) {
            String text = manuscript(paragraphs);
            ContextWindow w = ContextWindowPolicy.forContinue(BufferSnapshot.insertion(0, text, text.length()), Tier.BALANCED, "");
            assertTrue(w.before().length() <= 8_000, "window exceeded budget for " + paragraphs + " paragraphs");
            assertTrue(w.before().length() >= 6_000, "window snapped away too much for " + paragraphs + " paragraphs");
            assertTrue(w.beforeTruncated());
        }
    }

    @Test
    void windowStartsAtParagraphBoundary() {
        String text = manuscript(500);
        ContextWindow w = ContextWindowPolicy.forContinue(BufferSnapshot.insertion(0, text, text.length()), Tier.FAST, "");
        assertTrue(w.before().startsWith("Paragraph "), w.before().substring(0, 40));
    }

    @Test
    void shortDocumentIsSentWhole() {
        String text = "Short text. Only two sentences.";
        ContextWindow w = ContextWindowPolicy.forContinue(BufferSnapshot.insertion(0, text, text.length()), Tier.FAST, null);
        assertEquals(text, w.before());
        assertFalse(w.beforeTruncated());
        assertEquals("", w.after());
    }

    @Test
    void continuationInTheMiddleIncludesABitOfWhatFollows() {
        String text = manuscript(50);
        int caret = text.length() / 2;
        ContextWindow w = ContextWindowPolicy.forContinue(BufferSnapshot.insertion(0, text, caret), Tier.FAST, "");
        assertTrue(w.after().length() <= ContextWindowPolicy.CONTINUE_AFTER_CHARS);
        assertFalse(w.after().isEmpty());
    }

    @Test
    void rewriteSendsTargetAndBoundedSurroundings() {
        String text = manuscript(1_000);
        int start = text.length() / 2;
        Span target = new Span(start, start + 40);
        ContextWindow w = ContextWindowPolicy.forRewrite(BufferSnapshot.selection(0, text, target.start(), target.end()),
                target, Tier.BALANCED, "");
        assertEquals(text.substring(target.start(), target.end()), w.target());
        assertTrue(w.before().length() <= 2_000);
        assertTrue(w.after().length() <= 1_000);
    }

    @Test
    void wholeDocumentConsultIsCapped() {
        String text = manuscript(2_000);
        ContextWindow w = ContextWindowPolicy.forConsult(BufferSnapshot.insertion(0, text, 0), Tier.DEEP, "", true);
        assertTrue(w.target().length() <= ContextWindowPolicy.WHOLE_DOCUMENT_MAX_CHARS);
    }

    @Test
    void consultWithoutSelectionUsesNearbyParagraphs() {
        String text = manuscript(1_000);
        ContextWindow w = ContextWindowPolicy.forConsult(BufferSnapshot.insertion(0, text, text.length() / 2), Tier.FAST, "", false);
        assertTrue(w.target().length() <= ContextWindowPolicy.continueBudget(Tier.FAST));
        assertEquals("", w.before());
    }

    @Test
    void briefIsCapped() {
        String brief = "Character notes. ".repeat(500);
        ContextWindow w = ContextWindowPolicy.forContinue(BufferSnapshot.insertion(0, "x", 1), Tier.FAST, brief);
        assertTrue(w.brief().length() <= ContextWindowPolicy.BRIEF_MAX_CHARS);
    }

    @Test
    void neverSplitsASurrogatePair() {
        String emoji = "😀";
        String text = emoji.repeat(10_000);
        ContextWindow w = ContextWindowPolicy.forContinue(BufferSnapshot.insertion(0, text, text.length() - 1), Tier.FAST, "");
        assertFalse(Character.isLowSurrogate(w.before().charAt(0)));
    }
}
