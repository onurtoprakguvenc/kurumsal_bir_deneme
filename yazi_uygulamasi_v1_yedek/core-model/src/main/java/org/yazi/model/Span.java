package org.yazi.model;

/**
 * Half-open character range {@code [start, end)} inside a buffer. An empty span marks a caret position.
 */
public record Span(int start, int end) {

    public Span {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("invalid span [" + start + ", " + end + ")");
        }
    }

    /** Builds a span from two offsets in either order. */
    public static Span of(int a, int b) {
        return new Span(Math.min(a, b), Math.max(a, b));
    }

    public static Span caret(int offset) {
        return new Span(offset, offset);
    }

    public int length() {
        return end - start;
    }

    public boolean isEmpty() {
        return start == end;
    }

    /** Returns this span clamped into {@code [0, length]}. */
    public Span clampTo(int length) {
        int max = Math.max(0, length);
        return new Span(Math.min(start, max), Math.min(end, max));
    }
}
