package org.yazi.model;

/**
 * Immutable picture of the editor at the moment a request is made: full text, selection, caret and a revision
 * counter. The revision lets a splice detect that the user kept typing while the model was generating.
 *
 * <p>Offsets are clamped into the text, so a snapshot can never throw {@code StringIndexOutOfBoundsException}.
 * Without a selection, {@link #selection()} is the empty span at the caret.</p>
 */
public record BufferSnapshot(long revision, String text, Span selection, int caret) {

    public BufferSnapshot {
        text = (text == null) ? "" : text;
        int length = text.length();
        caret = clamp(caret, length);
        selection = (selection == null) ? Span.caret(caret) : selection.clampTo(length);
    }

    public static BufferSnapshot insertion(long revision, String text, int caret) {
        int offset = Math.max(0, caret);
        return new BufferSnapshot(revision, text, Span.caret(offset), offset);
    }

    public static BufferSnapshot selection(long revision, String text, int start, int end) {
        Span span = Span.of(Math.max(0, start), Math.max(0, end));
        return new BufferSnapshot(revision, text, span, span.end());
    }

    public boolean hasSelection() {
        return !selection.isEmpty();
    }

    public String selectedText() {
        return slice(selection);
    }

    public String slice(Span span) {
        Span clamped = span.clampTo(text.length());
        return text.substring(clamped.start(), clamped.end());
    }

    public int length() {
        return text.length();
    }

    private static int clamp(int value, int length) {
        return Math.max(0, Math.min(value, length));
    }

    @Override
    public String toString() {
        return "BufferSnapshot{rev=" + revision + ", length=" + text.length()
                + ", selection=[" + selection.start() + ", " + selection.end() + "), caret=" + caret + '}';
    }
}
