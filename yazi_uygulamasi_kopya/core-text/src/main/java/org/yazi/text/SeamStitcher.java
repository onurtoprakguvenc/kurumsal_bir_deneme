package org.yazi.text;

import static org.yazi.text.TextSignals.isDash;
import static org.yazi.text.TextSignals.isHorizontalSpace;
import static org.yazi.text.TextSignals.isQuote;
import static org.yazi.text.TextSignals.isTerminal;

/**
 * Fits generated text into the buffer at a splice point.
 *
 * <p>Only the two seams are touched (where the payload meets the text before and after it); the body of the
 * payload is never rewritten. Rules, in order:</p>
 * <ol>
 *   <li>Drop a quote or dash the model repeated from the buffer edge.</li>
 *   <li>Leading seam: add a space only when two words or sentences would otherwise run together. No space after
 *       an opening quote or bracket, a dash, a slash or an apostrophe ({@code Ankara'} + {@code da}), none inside
 *       a word, and none after punctuation when the writer's own style omits it.</li>
 *   <li>Trailing seam: avoid double spaces, and separate a finished clause from a following word.</li>
 * </ol>
 *
 * <p>Ported from {@code DiffMergeEngine.cleanBoundary} in writing_improve_v2, which always added a space after
 * any non-letter character (so {@code "} + {@code Hello} became {@code " Hello}) and dropped a model's opening
 * quote even when the buffer's quote was a closing one.</p>
 */
public final class SeamStitcher {

    private SeamStitcher() {}

    /** How much surrounding text is inspected to learn the writer's punctuation spacing habit. */
    private static final int STYLE_PROBE_CHARS = 2_000;

    public static String stitch(String before, String generated, String after) {
        String prev = (before == null) ? "" : before;
        String next = (after == null) ? "" : after;
        String text = (generated == null) ? "" : generated;

        text = dropRepeatedEdges(prev, text, next);
        if (text.isEmpty()) {
            return text;
        }
        boolean glues = TextSignals.gluesAfterPunctuation(styleProbe(prev, next));
        text = joinLeading(prev, text, next, glues);
        return joinTrailing(text, next, glues);
    }

    private static String dropRepeatedEdges(String before, String text, String after) {
        String out = text;
        if (!before.isEmpty() && !out.isEmpty()) {
            char last = before.charAt(before.length() - 1);
            char first = out.charAt(0);
            boolean repeatedQuote = opensQuotation(last, before) && isQuote(first);
            boolean repeatedDash = isDash(last) && isDash(first);
            if (repeatedQuote || repeatedDash) {
                out = out.substring(1);
            }
        }
        if (!after.isEmpty() && !out.isEmpty()) {
            if (isQuote(after.charAt(0)) && isQuote(out.charAt(out.length() - 1))) {
                out = out.substring(0, out.length() - 1);
            }
        }
        return out;
    }

    private static String joinLeading(String before, String text, String after, boolean glues) {
        if (before.isEmpty()) {
            return text.stripLeading();
        }
        char last = before.charAt(before.length() - 1);
        char first = text.charAt(0);

        if (isHorizontalSpace(last)) {
            return stripLeadingHorizontal(text);
        }
        if (Character.isWhitespace(last) || Character.isWhitespace(first)) {
            return text;
        }
        if (attachesToPrevious(first)) {
            return text;
        }
        if (opensGroup(last, before) || isDash(last) || last == '/' || last == '\'' || last == '’') {
            return text;
        }
        boolean insideWord = Character.isLetterOrDigit(last) && Character.isLetterOrDigit(first)
                && !after.isEmpty() && Character.isLetterOrDigit(after.charAt(0));
        if (insideWord) {
            return text;
        }
        if (glues && isGluablePunctuation(last) && Character.isLetter(first)) {
            return text;
        }
        return " " + text;
    }

    private static String joinTrailing(String text, String after, boolean glues) {
        if (after.isEmpty()) {
            return text;
        }
        char next = after.charAt(0);
        if (isHorizontalSpace(next)) {
            return stripTrailingHorizontal(text);
        }
        char end = text.charAt(text.length() - 1);
        if (Character.isWhitespace(end) || !Character.isLetterOrDigit(next)) {
            return text;
        }
        if (glues && isGluablePunctuation(end)) {
            return text;
        }
        return endsClause(end) ? text + " " : text;
    }

    /** Characters that belong to the word before them and must not be separated from it. */
    private static boolean attachesToPrevious(char c) {
        return c == ',' || c == ';' || c == ':' || isTerminal(c) || c == ')' || c == ']' || c == '}'
                || c == '”' || c == '»' || c == '’' || c == '\'' || c == '%' || isDash(c);
    }

    private static boolean opensGroup(char c, String before) {
        return c == '(' || c == '[' || c == '{' || c == '¿' || c == '¡' || opensQuotation(c, before);
    }

    private static boolean opensQuotation(char c, String before) {
        return switch (c) {
            case '“', '«', '‘', '„' -> true;
            case '"' -> TextSignals.hasOpenStraightQuote(before);
            case '\'' -> before.length() == 1 || Character.isWhitespace(before.charAt(before.length() - 2));
            default -> false;
        };
    }

    private static boolean isGluablePunctuation(char c) {
        return isTerminal(c) || c == ',' || c == ';' || c == ':';
    }

    private static boolean endsClause(char c) {
        return isGluablePunctuation(c) || c == ')' || c == ']' || c == '”' || c == '»';
    }

    private static String styleProbe(String before, String after) {
        String tail = before.substring(Math.max(0, before.length() - STYLE_PROBE_CHARS));
        String head = after.substring(0, Math.min(after.length(), STYLE_PROBE_CHARS / 4));
        return tail + "\n" + head;
    }

    private static String stripLeadingHorizontal(String s) {
        int i = 0;
        while (i < s.length() && isHorizontalSpace(s.charAt(i))) {
            i++;
        }
        return s.substring(i);
    }

    private static String stripTrailingHorizontal(String s) {
        int end = s.length();
        while (end > 0 && isHorizontalSpace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }
}
