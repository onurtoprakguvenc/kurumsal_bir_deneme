package org.yazi.text;

import org.yazi.model.BufferSnapshot;
import org.yazi.model.Span;

/**
 * Pure, atomic splicing of a generated payload into a buffer. The single owner of buffer coordinates: pipelines
 * return text, this class decides where it lands and where the caret goes.
 */
public final class SpliceEngine {

    private SpliceEngine() {}

    /**
     * @param text     the new buffer text
     * @param inserted where the (seam-adjusted) payload now sits; its end is the new caret
     */
    public record SpliceResult(String text, Span inserted) {
        public int caret() {
            return inserted.end();
        }
    }

    /**
     * Replaces {@code target} with {@code generated}. An empty target is an insertion at that offset.
     * Offsets are clamped into the text.
     */
    public static SpliceResult splice(String base, Span target, String generated) {
        String text = (base == null) ? "" : base;
        Span span = target.clampTo(text.length());
        String before = text.substring(0, span.start());
        String after = text.substring(span.end());
        String payload = SeamStitcher.stitch(before, generated, after);

        String updated = new StringBuilder(before.length() + payload.length() + after.length())
                .append(before).append(payload).append(after)
                .toString();
        return new SpliceResult(updated, new Span(span.start(), span.start() + payload.length()));
    }

    /**
     * Splices against the snapshot the request was built from, refusing when the editor has moved on.
     *
     * @param currentRevision the editor's revision at the moment the result is ready
     */
    public static SpliceResult splice(BufferSnapshot snapshot, long currentRevision, Span target, String generated)
            throws StaleBufferException {
        if (snapshot.revision() != currentRevision) {
            throw new StaleBufferException(snapshot.revision(), currentRevision);
        }
        return splice(snapshot.text(), target, generated);
    }
}
