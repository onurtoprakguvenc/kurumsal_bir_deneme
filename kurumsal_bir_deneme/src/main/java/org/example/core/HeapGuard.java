package org.example.core;

import java.util.concurrent.TimeUnit;

/**
 * Dynamic memory ceiling for ingestion.
 *
 * <p>Extraction is streaming, but the extracted text itself lives in the RAM index, so a very large document can
 * still push the heap towards its limit. Before each file and periodically while text is flowing, the guard
 * compares used heap against {@code Runtime.getRuntime().maxMemory()}. Above the ceiling it asks the collector
 * once (at most every {@value #GC_COOLDOWN_SECONDS} s) and re-measures, so transient garbage does not abort a
 * healthy ingestion; if the pressure is real, the parser refuses the file or stops the extraction gracefully with
 * a note instead of dying with {@link OutOfMemoryError}.</p>
 */
public final class HeapGuard {

    private static final int GC_COOLDOWN_SECONDS = 5;

    private final double ceiling;
    /** Guards are shared between ingestion threads; {@code 0} means "never collected". */
    private volatile long lastCollectionNanos;

    /** Default ceiling: refuse new work above 85 % of the maximum heap. */
    public static final double DEFAULT_CEILING = 0.85;
    /** Headroom always kept free on top of an estimated allocation. */
    private static final long MARGIN_BYTES = 32L << 20;

    public HeapGuard(double ceiling) {
        this.ceiling = ceiling;
    }

    /** Used heap as a fraction of the maximum heap (0..1). */
    public static double usageRatio() {
        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        if (max <= 0 || max == Long.MAX_VALUE) {
            return 0;
        }
        return (double) (runtime.totalMemory() - runtime.freeMemory()) / max;
    }

    public double ceiling() {
        return ceiling;
    }

    /** True when the heap is above the ceiling even after a collection hint. */
    public boolean underPressure() {
        if (usageRatio() <= ceiling) {
            return false;
        }
        long now = System.nanoTime();
        long last = lastCollectionNanos;
        if (last == 0 || now - last > TimeUnit.SECONDS.toNanos(GC_COOLDOWN_SECONDS)) {
            lastCollectionNanos = now;
            System.gc();
        }
        return usageRatio() > ceiling;
    }

    /** Free heap: maximum heap minus what is currently in use. */
    public static long freeHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        if (max <= 0 || max == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, max - (runtime.totalMemory() - runtime.freeMemory()));
    }

    /**
     * True when an allocation of {@code requiredBytes} still leaves {@value #MARGIN_BYTES} bytes of headroom.
     * A collection is requested once before refusing, so garbage does not cause a false negative.
     */
    public boolean hasHeadroom(long requiredBytes) {
        if (freeHeapBytes() >= requiredBytes + MARGIN_BYTES) {
            return true;
        }
        underPressure();
        return freeHeapBytes() >= requiredBytes + MARGIN_BYTES;
    }

    /** Human-readable heap state for error messages and notes. */
    public String describe() {
        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        long used = runtime.totalMemory() - runtime.freeMemory();
        return Math.round(usageRatio() * 100) + "% of " + (max >> 20) + " MB in use ("
                + (used >> 20) + " MB), ceiling " + Math.round(ceiling * 100) + "%";
    }
}
