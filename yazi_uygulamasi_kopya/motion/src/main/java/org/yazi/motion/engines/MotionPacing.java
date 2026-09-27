package org.yazi.motion.engines;

/** Engine-internal pacing classification; rendered into TemporalSegment.pacing_note and movement_speed. */
public enum MotionPacing {
    SLOW_MOTION,
    REALTIME,
    FAST_PACED,
    SLOW_PACED,
    TIMELAPSE,
    SPEED_RAMP
}
