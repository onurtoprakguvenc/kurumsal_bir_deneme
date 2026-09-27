package org.yazi.motion.domain;

import static org.yazi.motion.domain.DomainValidation.*;

public record TemporalSegment(
        int segmentIndex,
        double startSecond,
        double endSecond,
        double durationSec,
        String description,
        CameraKinematics kinematics,
        String pacingNote) {

    public TemporalSegment {
        if (segmentIndex < 0) throw new IllegalArgumentException("segment_index must be >= 0");
        if (startSecond < 0 || !(endSecond > startSecond)) {
            throw new IllegalArgumentException("segment " + segmentIndex + " must satisfy 0 <= start < end");
        }
        if (!(durationSec > 0)) throw new IllegalArgumentException("duration_sec must be positive");
        requireText(description, "description");
        require(kinematics, "kinematics");
        requireText(pacingNote, "pacing_note");
    }
}
