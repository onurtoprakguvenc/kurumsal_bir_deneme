package org.yazi.text;

import org.yazi.model.Span;

import java.util.Optional;

/** Finds structural blocks in the buffer, ported from {@code BufferGeometryResolver.extractPrecedingBlock}. */
public final class BlockLocator {

    private BlockLocator() {}

    /**
     * The paragraph that ends at or before {@code caret} (blocks are separated by a blank line), trimmed of
     * surrounding whitespace. Empty when there is no text before the caret.
     */
    public static Optional<Span> precedingBlock(String text, int caret) {
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        int length = text.length();
        int end = (caret <= 0 || caret > length) ? length : caret;
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        if (end == 0) {
            return Optional.empty();
        }

        int start = end;
        while (start > 0 && !isBlankLineBefore(text, start)) {
            start--;
        }
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        return (start < end) ? Optional.of(new Span(start, end)) : Optional.empty();
    }

    /** True when the characters just before {@code index} are "\n\n" or "\n\r\n". */
    private static boolean isBlankLineBefore(String text, int index) {
        if (text.charAt(index - 1) != '\n') {
            return false;
        }
        if (index >= 2 && text.charAt(index - 2) == '\n') {
            return true;
        }
        return index >= 3 && text.charAt(index - 2) == '\r' && text.charAt(index - 3) == '\n';
    }
}
