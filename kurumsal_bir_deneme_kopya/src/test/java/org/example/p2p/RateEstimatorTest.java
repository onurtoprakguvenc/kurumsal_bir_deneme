package org.example.p2p;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Smoothed throughput on a synthetic clock: steady state, speed changes, stalls, restarts and ETA. */
class RateEstimatorTest {

    private static final long MS = 1_000_000L;
    private static final double MB = 1024 * 1024;

    /** Feeds {@code seconds} of a constant {@code mbPerSecond}, one sample per 100 ms; returns the new byte count. */
    private static long feed(RateEstimator r, long bytes, long[] clock, double mbPerSecond, double seconds) {
        for (int i = 0; i < seconds * 10; i++) {
            clock[0] += 100 * MS;
            bytes += (long) (mbPerSecond * MB / 10);
            r.sample(bytes, clock[0]);
        }
        return bytes;
    }

    @Test
    void unmeasuredUntilTheFirstFullInterval() {
        RateEstimator r = new RateEstimator();
        assertFalse(r.measured());
        assertEquals(0, r.bytesPerSecond(0));
        r.sample(0, 0);
        r.sample(1_000_000, 100 * MS); // closer than the sampling interval: merged into the next sample
        assertFalse(r.measured());
        r.sample(3_000_000, 300 * MS);
        assertTrue(r.measured());
        assertEquals(10_000_000, r.bytesPerSecond(300 * MS), 1, "first interval taken as is: 3 MB in 0.3 s");
    }

    @Test
    void convergesToASteadyRate() {
        RateEstimator r = new RateEstimator();
        long[] clock = {0};
        r.sample(0, 0);
        feed(r, 0, clock, 50, 10);
        assertEquals(50 * MB, r.bytesPerSecond(clock[0]), 50 * MB * 0.01);
    }

    @Test
    void followsASpeedDropWithinAFewTimeConstants() {
        RateEstimator r = new RateEstimator();
        long[] clock = {0};
        r.sample(0, 0);
        long bytes = feed(r, 0, clock, 100, 10);
        bytes = feed(r, bytes, clock, 10, 3); // one time constant after the drop: ~63 % of the way
        double afterOne = r.bytesPerSecond(clock[0]);
        assertTrue(afterOne < 50 * MB && afterOne > 30 * MB, "after τ: " + afterOne / MB);
        feed(r, bytes, clock, 10, 20);
        assertEquals(10 * MB, r.bytesPerSecond(clock[0]), 10 * MB * 0.05, "settled on the new speed");
    }

    @Test
    void decaysWhileStalledAndRecoversAfterwards() {
        RateEstimator r = new RateEstimator(100 * MS, RateEstimator.TIME_CONSTANT_NANOS); // one sample per feed step
        long[] clock = {0};
        r.sample(0, 0);
        long bytes = feed(r, 0, clock, 40, 5);
        double steady = r.bytesPerSecond(clock[0]);
        assertEquals(steady, r.bytesPerSecond(clock[0] + 90 * MS), 1e-6, "no decay within one sampling interval");
        double stalled = r.bytesPerSecond(clock[0] + 10_000 * MS);
        assertTrue(stalled < steady * 0.05, "ten seconds without bytes reads as (nearly) stopped: " + stalled / MB);
        clock[0] += 10_000 * MS;
        r.sample(bytes, clock[0]); // the stall itself arrives as one zero-throughput sample
        assertTrue(r.bytesPerSecond(clock[0]) < steady * 0.05);
        feed(r, bytes, clock, 40, 12);
        assertEquals(40 * MB, r.bytesPerSecond(clock[0]), 40 * MB * 0.05);
    }

    @Test
    void aCounterGoingBackwardsStartsANewBaseline() {
        RateEstimator r = new RateEstimator();
        long[] clock = {0};
        r.sample(0, 0);
        feed(r, 0, clock, 20, 3);
        clock[0] += 300 * MS;
        r.sample(0, clock[0]); // a resume that restarted at byte 0
        assertTrue(r.bytesPerSecond(clock[0]) > 0, "the previous estimate stays until new bytes are measured");
        feed(r, 0, clock, 20, 6);
        assertEquals(20 * MB, r.bytesPerSecond(clock[0]), 20 * MB * 0.05);
    }

    @Test
    void etaSeconds() {
        assertEquals(0, RateEstimator.etaSeconds(0, 0));
        assertEquals(-1, RateEstimator.etaSeconds(100, 0));
        assertEquals(-1, RateEstimator.etaSeconds(100, Double.NaN));
        assertEquals(10, RateEstimator.etaSeconds(1_000, 100));
        assertEquals(11, RateEstimator.etaSeconds(1_001, 100), "rounded up");
        assertEquals(99L * 24 * 3600, RateEstimator.etaSeconds(Long.MAX_VALUE, 0.01), "capped");
    }

    @Test
    void transferMeterReportsCurrentRateAndEta() throws InterruptedException {
        TransferMeter meter = new TransferMeter();
        assertFalse(meter.snapshot().started());
        long total = 100L << 20;
        long done = 0;
        long started = System.nanoTime();
        while (System.nanoTime() - started < 700 * MS) {
            done += 256 << 10;
            meter.update(Math.min(done, total / 2), total);
            Thread.sleep(10);
        }
        TransferMeter.Snapshot s = meter.snapshot();
        assertTrue(s.started());
        assertTrue(s.bytesPerSecond() > 0);
        assertTrue(s.currentBytesPerSecond() > 0, "measured after more than one sampling interval");
        assertTrue(s.etaSeconds() >= 0);
        assertEquals(s.currentBytesPerSecond() / MB, s.currentMegabytesPerSecond(), 1e-9);
    }
}
