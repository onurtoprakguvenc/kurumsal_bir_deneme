package org.yazi.motion.engines;

import org.yazi.motion.domain.TemporalSegment;
import org.yazi.motion.domain.exception.TemporalDecompositionException;
import org.yazi.motion.domain.exception.TemporalGapException;

import java.util.List;
import java.util.Map;

/** Temporal constants and the continuity guard shared by the decomposer and the compiler. */
public final class TemporalContinuity {
    private TemporalContinuity() {}

    public static final double MIN_DURATION_SEC = 1.0;
    public static final double MAX_DURATION_SEC = 60.0;
    /** Boundaries are rounded to milliseconds; comparisons tolerate 1 ms. */
    public static final double TOLERANCE_SEC = 0.001;
    public static final double MIN_SEGMENT_SEC = 2.0;
    public static final double MAX_SEGMENT_SEC = 5.0;

    public static double roundToMillis(double seconds) {
        return Math.round(seconds * 1000.0) / 1000.0;
    }

    public static void assertTemporalContinuity(List<TemporalSegment> segments, double targetDurationSec) {
        if (segments == null || segments.isEmpty()) {
            throw new TemporalDecompositionException("Temporal segment collection cannot be empty.");
        }
        if (Math.abs(segments.getFirst().startSecond()) > TOLERANCE_SEC) {
            throw new TemporalGapException("Timeline must begin at second 0.000.",
                    Map.of("actual_start", segments.getFirst().startSecond()));
        }
        for (int i = 0; i < segments.size() - 1; i++) {
            double currentEnd = segments.get(i).endSecond();
            double nextStart = segments.get(i + 1).startSecond();
            if (Math.abs(currentEnd - nextStart) > TOLERANCE_SEC) {
                throw new TemporalGapException(
                        "Temporal discontinuity detected between segment " + i + " and " + (i + 1) + ".",
                        Map.of("segment_end", currentEnd, "next_segment_start", nextStart));
            }
        }
        double finalEnd = segments.getLast().endSecond();
        if (Math.abs(finalEnd - targetDurationSec) > TOLERANCE_SEC) {
            throw new TemporalGapException("Final segment end time does not match total duration target.",
                    Map.of("final_segment_end", finalEnd, "target_duration", targetDurationSec));
        }
    }
}
