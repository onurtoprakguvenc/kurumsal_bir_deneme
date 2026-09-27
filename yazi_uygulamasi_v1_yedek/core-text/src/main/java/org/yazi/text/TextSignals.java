package org.yazi.text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small character-level observations shared by the seam and style code. */
final class TextSignals {

    private TextSignals() {}

    private static final Pattern GLUED_AFTER_PUNCTUATION = Pattern.compile("[.!?,;:…](?=\\p{L})");
    private static final Pattern SPACED_AFTER_PUNCTUATION = Pattern.compile("[.!?,;:…][ \\t]+(?=\\p{L})");

    /**
     * True when the writer habitually omits the space after punctuation ("i know.who denies that.know,back").
     * Needs at least two glued occurrences and more glued than spaced ones, so the odd abbreviation
     * ("e.g.") does not flip the verdict.
     */
    static boolean gluesAfterPunctuation(CharSequence text) {
        int glued = count(GLUED_AFTER_PUNCTUATION, text);
        int spaced = count(SPACED_AFTER_PUNCTUATION, text);
        return glued >= 2 && glued > spaced;
    }

    /** True when the last straight double quote in the current paragraph opens a quotation. */
    static boolean hasOpenStraightQuote(String text) {
        int paragraphStart = text.lastIndexOf('\n') + 1;
        int quotes = 0;
        for (int i = paragraphStart; i < text.length(); i++) {
            if (text.charAt(i) == '"') {
                quotes++;
            }
        }
        return quotes % 2 == 1;
    }

    static boolean isHorizontalSpace(char c) {
        return c == ' ' || c == '\t' || c == ' ';
    }

    static boolean isDash(char c) {
        return c == '-' || c == '–' || c == '—';
    }

    static boolean isQuote(char c) {
        return c == '"' || c == '“' || c == '”' || c == '«' || c == '»'
                || c == '‘' || c == '’' || c == '„';
    }

    static boolean isTerminal(char c) {
        return c == '.' || c == '!' || c == '?' || c == '…';
    }

    private static int count(Pattern pattern, CharSequence text) {
        Matcher m = pattern.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }
}
