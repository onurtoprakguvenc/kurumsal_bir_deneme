package org.yazi.text;

/**
 * The buffer changed after the request was built, so its offsets no longer describe the current text.
 * The caller should show the result elsewhere instead of splicing it.
 */
public final class StaleBufferException extends Exception {

    private final long expectedRevision;
    private final long actualRevision;

    public StaleBufferException(long expectedRevision, long actualRevision) {
        super("Buffer changed during generation (revision " + expectedRevision + " -> " + actualRevision + ").");
        this.expectedRevision = expectedRevision;
        this.actualRevision = actualRevision;
    }

    public long expectedRevision() {
        return expectedRevision;
    }

    public long actualRevision() {
        return actualRevision;
    }
}
