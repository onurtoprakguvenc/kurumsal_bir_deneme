package org.yazi.text;

import org.yazi.model.BufferSnapshot;
import org.yazi.model.ContextWindow;
import org.yazi.model.Span;
import org.yazi.model.Tier;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides exactly which slice of the buffer a model sees. Budgets are fixed per tier, so the cost of an operation
 * does not grow with the length of the document.
 *
 * <p>Replaces {@code UnifiedPipelineRouter.calculateDynamicContextRadius} from writing_improve_v2, which sent a
 * percentage of the manuscript (up to all of it) and therefore got more expensive, and less focused, with every
 * page written.</p>
 *
 * <p>Cuts are snapped to a paragraph break, then a sentence end, then a word boundary, searched within the first
 * (or last) quarter of the window, so the model never starts reading mid-word.</p>
 */
public final class ContextWindowPolicy {

    private ContextWindowPolicy() {}

    public static final int BRIEF_MAX_CHARS = 1_500;
    public static final int CONTINUE_AFTER_CHARS = 500;
    public static final int WHOLE_DOCUMENT_MAX_CHARS = 64_000;

    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n[ \\t\\r]*\\n");

    /** Characters of preceding text for a continuation. */
    public static int continueBudget(Tier tier) {
        return switch (tier) {
            case FAST -> 4_000;
            case BALANCED -> 8_000;
            case DEEP -> 16_000;
        };
    }

    /** Characters of context on the leading side of a rewrite; the trailing side gets half. */
    public static int rewriteBudget(Tier tier) {
        return switch (tier) {
            case FAST -> 1_000;
            case BALANCED -> 2_000;
            case DEEP -> 4_000;
        };
    }

    /** Continue writing at the caret. Any selection is ignored. */
    public static ContextWindow forContinue(BufferSnapshot snapshot, Tier tier, String brief) {
        String text = snapshot.text();
        int caret = snapshot.caret();
        int start = snapStart(text, caret - continueBudget(tier), caret);
        int end = snapEnd(text, caret, caret + CONTINUE_AFTER_CHARS);
        return new ContextWindow(text.substring(start, caret), "", text.substring(caret, end), capBrief(brief), start > 0);
    }

    /** Transform {@code target} (usually the selection or a located block). */
    public static ContextWindow forRewrite(BufferSnapshot snapshot, Span target, Tier tier, String brief) {
        String text = snapshot.text();
        Span t = target.clampTo(text.length());
        int budget = rewriteBudget(tier);
        int start = snapStart(text, t.start() - budget, t.start());
        int end = snapEnd(text, t.end(), t.end() + budget / 2);
        return new ContextWindow(text.substring(start, t.start()), text.substring(t.start(), t.end()),
                text.substring(t.end(), end), capBrief(brief), start > 0);
    }

    /**
     * Ask about the text without changing it. With a selection, the selection is the subject. Without one, the
     * paragraphs around the caret are. {@code wholeDocument} is an explicit user choice and is still capped.
     */
    public static ContextWindow forConsult(BufferSnapshot snapshot, Tier tier, String brief, boolean wholeDocument) {
        String text = snapshot.text();
        if (wholeDocument) {
            if (text.length() <= WHOLE_DOCUMENT_MAX_CHARS) {
                return new ContextWindow("", text, "", capBrief(brief), false);
            }
            int rawStart = Math.max(0, Math.min(snapshot.caret() - WHOLE_DOCUMENT_MAX_CHARS / 2,
                    text.length() - WHOLE_DOCUMENT_MAX_CHARS));
            int start = snapStart(text, rawStart, text.length());
            int end = snapEnd(text, start, start + WHOLE_DOCUMENT_MAX_CHARS);
            return new ContextWindow("", text.substring(start, end), "", capBrief(brief), start > 0);
        }
        if (snapshot.hasSelection()) {
            return forRewrite(snapshot, snapshot.selection(), tier, brief);
        }
        int caret = snapshot.caret();
        int budget = continueBudget(tier);
        int start = snapStart(text, caret - budget * 2 / 3, caret);
        int end = snapEnd(text, caret, caret + budget / 3);
        return new ContextWindow("", text.substring(start, end), "", capBrief(brief), start > 0);
    }

    static String capBrief(String brief) {
        if (brief == null) {
            return "";
        }
        String b = brief.strip();
        if (b.length() <= BRIEF_MAX_CHARS) {
            return b;
        }
        return b.substring(0, snapEnd(b, 0, BRIEF_MAX_CHARS)).strip();
    }

    /** Moves a raw window start forward to a clean boundary, looking no further than a quarter of the window. */
    static int snapStart(String text, int rawStart, int limit) {
        if (rawStart <= 0) {
            return 0;
        }
        int searchEnd = Math.min(limit, rawStart + Math.max(1, (limit - rawStart) / 4));

        Matcher paragraph = PARAGRAPH_BREAK.matcher(text).region(rawStart, searchEnd);
        if (paragraph.find()) {
            return skipWhitespace(text, paragraph.end(), limit);
        }
        for (int i = rawStart; i < searchEnd - 1; i++) {
            if (TextSignals.isTerminal(text.charAt(i)) && Character.isWhitespace(text.charAt(i + 1))) {
                return skipWhitespace(text, i + 1, limit);
            }
        }
        for (int i = rawStart; i < searchEnd; i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return skipWhitespace(text, i, limit);
            }
        }
        return Character.isLowSurrogate(text.charAt(rawStart)) ? rawStart + 1 : rawStart;
    }

    /** Moves a raw window end backward to a clean boundary, looking no further back than a quarter of the window. */
    static int snapEnd(String text, int from, int rawEnd) {
        if (rawEnd >= text.length()) {
            return text.length();
        }
        int searchStart = Math.max(from, rawEnd - Math.max(1, (rawEnd - from) / 4));

        int lastParagraph = -1;
        Matcher paragraph = PARAGRAPH_BREAK.matcher(text).region(searchStart, rawEnd);
        while (paragraph.find()) {
            lastParagraph = paragraph.start();
        }
        if (lastParagraph >= 0) {
            return lastParagraph;
        }
        for (int i = rawEnd - 1; i > searchStart; i--) {
            if (TextSignals.isTerminal(text.charAt(i - 1)) && Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        for (int i = rawEnd - 1; i >= searchStart; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return Character.isHighSurrogate(text.charAt(rawEnd - 1)) ? rawEnd - 1 : rawEnd;
    }

    private static int skipWhitespace(String text, int index, int limit) {
        int i = index;
        while (i < limit && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }
}
