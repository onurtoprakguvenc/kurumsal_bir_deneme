package org.yazi.prose;

import org.yazi.model.Tier;
import org.yazi.text.StyleProfile;

/**
 * Temperature and output ceilings per intent and tier. Fixed, small numbers: the ceiling is a safety net, the
 * instruction ("at most one paragraph") is what actually keeps output short.
 *
 * <p>Continuation stays within 0.35–0.45 by the writer's decision: higher values drifted and invented detail.</p>
 */
final class Sampling {

    private Sampling() {}

    static final double CONTINUE_MIN = 0.35;
    static final double CONTINUE_MAX = 0.45;

    static double continueTemperature(Tier tier, StyleProfile style) {
        double base = switch (tier) {
            case FAST -> 0.45;
            case BALANCED -> 0.40;
            case DEEP -> 0.35;
        };
        // A very even meter is easy to break; sample a little tighter to keep it, never below the floor.
        double t = (style.isMeasured() && style.sentenceLengthStdDev() < 1.0) ? base - 0.05 : base;
        return Math.max(CONTINUE_MIN, Math.min(CONTINUE_MAX, t));
    }

    static int continueMaxTokens(Tier tier) {
        return switch (tier) {
            case FAST -> 512;
            case BALANCED -> 1_024;
            case DEEP -> 2_048;
        };
    }

    static final double REWRITE_TEMPERATURE = 0.35;
    static final double CONSULT_TEMPERATURE = 0.25;

    /** Room for the replacement to grow to about twice the target, bounded by the tier. */
    static int rewriteMaxTokens(Tier tier, int targetChars) {
        int ceiling = switch (tier) {
            case FAST -> 2_048;
            case BALANCED -> 4_096;
            case DEEP -> 8_192;
        };
        return Math.max(256, Math.min(ceiling, targetChars / 2 + 256));
    }

    static int consultMaxTokens(Tier tier) {
        return switch (tier) {
            case FAST -> 1_024;
            case BALANCED -> 2_048;
            case DEEP -> 4_096;
        };
    }
}
