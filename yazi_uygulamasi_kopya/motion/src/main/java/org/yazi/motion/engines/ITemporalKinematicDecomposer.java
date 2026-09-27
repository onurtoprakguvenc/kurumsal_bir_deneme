package org.yazi.motion.engines;

import org.yazi.motion.domain.TemporalSegment;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface ITemporalKinematicDecomposer {
    /**
     * Decomposes user intent into continuous, millisecond-accurate temporal segments.
     * The returned future completes exceptionally with:
     * {@link org.yazi.motion.domain.exception.TemporalDecompositionException} if the total duration is not accounted for,
     * {@link org.yazi.motion.domain.exception.TemporalGapException} if the continuity invariant is broken.
     */
    CompletableFuture<List<TemporalSegment>> decomposeTimeline(ParsedIntentResult intent);
}
