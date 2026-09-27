package org.yazi.desktop;

/**
 * Smooths streamed text into an even flow. Pure and FX-free; the caller supplies the clock.
 *
 * <p>The network delivers text in bursts (often a sentence at once, then nothing for a while). Showing each burst
 * the moment it lands makes the ghost text jump. Instead, arrivals go into a backlog, and every frame reveals a
 * share of it proportional to its size, so the backlog shrinks with a time constant of {@link #CATCH_UP_SECONDS}
 * (a 300-character burst flows out over roughly half a second), and never slower than
 * {@link #MIN_CHARS_PER_SECOND}. The flow therefore follows the model's speed with a lag of about a quarter
 * second, and a finished stream is always {@linkplain #flush() flushed} at once, so pacing never delays a
 * result.</p>
 *
 * <p>A reveal never ends between the two halves of a surrogate pair.</p>
 */
final class StreamPacer {

    static final double CATCH_UP_SECONDS = 0.25;
    static final double MIN_CHARS_PER_SECOND = 90;
    /** Assumed frame time for the first reveal, when there is no previous frame to measure from. */
    static final long FIRST_FRAME_NANOS = 16_666_667L;

    private final StringBuilder backlog = new StringBuilder();
    private long lastNanos = -1;

    void offer(String fragment) {
        if (fragment != null) {
            backlog.append(fragment);
        }
    }

    boolean isEmpty() {
        return backlog.isEmpty();
    }

    int backlog() {
        return backlog.length();
    }

    /** The text to reveal in the frame at {@code nowNanos}; empty when nothing is waiting. */
    String take(long nowNanos) {
        if (backlog.isEmpty()) {
            lastNanos = nowNanos;
            return "";
        }
        long elapsed = (lastNanos < 0) ? FIRST_FRAME_NANOS : Math.max(0, nowNanos - lastNanos);
        lastNanos = nowNanos;
        double seconds = elapsed / 1e9;
        double rate = Math.max(MIN_CHARS_PER_SECOND, backlog.length() / CATCH_UP_SECONDS);
        int n = (int) Math.min(backlog.length(), Math.max(1, Math.ceil(rate * seconds)));
        if (Character.isHighSurrogate(backlog.charAt(n - 1))) {
            if (n < backlog.length()) {
                n++;                  // take the whole pair
            } else if (--n == 0) {
                return "";            // the low half has not arrived yet (a fragment ended mid-pair); wait for it
            }
        }
        String out = backlog.substring(0, n);
        backlog.delete(0, n);
        return out;
    }

    /** Everything still waiting, at once (end of stream, cancel). */
    String flush() {
        String out = backlog.toString();
        backlog.setLength(0);
        lastNanos = -1;
        return out;
    }
}
