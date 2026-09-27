package org.example.p2p;

/**
 * Smoothed "right now" throughput of one transfer, for speed and time-left displays.
 *
 * <p>A session average reacts slowly: after a Wi-Fi hiccup or when a second transfer starts sharing the link, it keeps
 * promising the old speed for minutes. This estimator is a time-aware exponential moving average over samples at
 * least {@link #MIN_SAMPLE_NANOS} apart: each sample's weight is {@code 1 - e^(-Δt/τ)} with {@code τ =}
 * {@link #TIME_CONSTANT_NANOS}, so the result means the same whatever the sampling rate. While no bytes arrive the
 * reading decays towards zero by the same law, so a stalled transfer shows as stalled instead of frozen at its last
 * speed.</p>
 *
 * <p>Threading: exactly one thread calls {@link #sample} (the transfer thread, or the FX thread for UI-side
 * estimators); any thread may call {@link #bytesPerSecond} at any time. The writer only stores a few primitives —
 * no locks, no allocation — so it can sit on a 64 KiB-per-block hot path.</p>
 */
public final class RateEstimator {

    /** Samples closer together than this are merged into the next one (keeps per-block calls cheap and stable). */
    public static final long MIN_SAMPLE_NANOS = 250_000_000L;
    /** Smoothing time constant: about two thirds of a speed change shows within this time. */
    public static final long TIME_CONSTANT_NANOS = 3_000_000_000L;

    private final long minSampleNanos;
    private final double timeConstantNanos;

    // Writer-only state.
    private long lastBytes = -1;
    private long lastNanos;

    // Published to readers.
    private volatile double rate;
    private volatile long rateAtNanos;
    private volatile boolean measured;

    public RateEstimator() {
        this(MIN_SAMPLE_NANOS, TIME_CONSTANT_NANOS);
    }

    RateEstimator(long minSampleNanos, long timeConstantNanos) {
        if (minSampleNanos < 0 || timeConstantNanos <= 0) {
            throw new IllegalArgumentException("invalid smoothing parameters");
        }
        this.minSampleNanos = minSampleNanos;
        this.timeConstantNanos = timeConstantNanos;
    }

    /** Records that {@code totalBytes} have been moved so far (monotonic), observed at {@code nowNanos}. */
    public void sample(long totalBytes, long nowNanos) {
        if (lastBytes < 0 || totalBytes < lastBytes) {
            // First sample, or the counter went backwards (a resume that restarted at 0): new baseline.
            lastBytes = totalBytes;
            lastNanos = nowNanos;
            return;
        }
        long dt = nowNanos - lastNanos;
        if (dt < minSampleNanos || dt <= 0) {
            return;
        }
        double instant = (totalBytes - lastBytes) * 1e9 / dt;
        double next;
        if (!measured) {
            next = instant; // no history yet: the first interval is the best estimate there is
        } else {
            double alpha = 1 - Math.exp(-dt / timeConstantNanos);
            next = rate + alpha * (instant - rate);
        }
        lastBytes = totalBytes;
        lastNanos = nowNanos;
        rate = next;
        rateAtNanos = nowNanos;
        measured = true;
    }

    /** Convenience for {@link #sample(long, long)} with the current time. */
    public void sample(long totalBytes) {
        sample(totalBytes, System.nanoTime());
    }

    /** Whether at least one interval has been measured. */
    public boolean measured() {
        return measured;
    }

    /**
     * Smoothed rate at {@code nowNanos}; 0 until measured. When no sample arrived for longer than the sampling
     * interval, the last value decays as if the silent time had been one sample of zero throughput.
     */
    public double bytesPerSecond(long nowNanos) {
        if (!measured) {
            return 0;
        }
        double r = rate;
        long silent = nowNanos - rateAtNanos - minSampleNanos;
        return silent > 0 ? r * Math.exp(-silent / timeConstantNanos) : r;
    }

    public double bytesPerSecond() {
        return bytesPerSecond(System.nanoTime());
    }

    /**
     * Seconds needed for {@code remainingBytes} at {@code bytesPerSecond}: 0 when nothing is left, -1 when unknown
     * (no measurable rate). Rounded up, capped at 99 days so a near-zero rate cannot overflow a display.
     */
    public static long etaSeconds(long remainingBytes, double bytesPerSecond) {
        if (remainingBytes <= 0) {
            return 0;
        }
        if (!(bytesPerSecond > 1e-3)) {
            return -1;
        }
        return (long) Math.min(Math.ceil(remainingBytes / bytesPerSecond), 99L * 24 * 3600);
    }
}
