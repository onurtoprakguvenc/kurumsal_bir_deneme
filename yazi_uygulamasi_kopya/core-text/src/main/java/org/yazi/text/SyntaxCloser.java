package org.yazi.text;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Closes quotes and brackets left open when a generation is cut off. Only ever appends; never removes text.
 *
 * <p>Call this only when the model stopped because of its token ceiling (finish reason MAX_TOKENS). A complete
 * answer may leave a quote open on purpose, e.g. a multi-paragraph speech. Only the last paragraph is inspected
 * for the same reason.</p>
 */
public final class SyntaxCloser {

    private SyntaxCloser() {}

    public static String closeHanging(String text) {
        if (text == null || text.isEmpty()) {
            return (text == null) ? "" : text;
        }
        Deque<Character> open = new ArrayDeque<>();
        for (int i = text.lastIndexOf('\n') + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '(', '[', '“', '«' -> open.push(c);
                case '"' -> {
                    if (open.contains('"')) {
                        closeThrough(open, '"');
                    } else {
                        open.push('"');
                    }
                }
                case ')' -> closeThrough(open, '(');
                case ']' -> closeThrough(open, '[');
                case '”' -> closeThrough(open, '“');
                case '»' -> closeThrough(open, '«');
                default -> { }
            }
        }
        if (open.isEmpty()) {
            return text;
        }
        StringBuilder closed = new StringBuilder(text);
        while (!open.isEmpty()) {
            closed.append(closerFor(open.pop()));
        }
        return closed.toString();
    }

    /** Pops up to and including {@code opener}; openers nested inside it are treated as abandoned. */
    private static void closeThrough(Deque<Character> open, char opener) {
        if (!open.contains(opener)) {
            return;
        }
        while (open.pop() != opener) {
            // discard abandoned inner openers
        }
    }

    private static char closerFor(char opener) {
        return switch (opener) {
            case '(' -> ')';
            case '[' -> ']';
            case '“' -> '”';
            case '«' -> '»';
            default -> '"';
        };
    }
}
