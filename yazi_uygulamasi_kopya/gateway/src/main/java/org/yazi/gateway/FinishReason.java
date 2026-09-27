package org.yazi.gateway;

import java.util.Set;

/** Why the model stopped. */
public enum FinishReason {
    /** Natural end. */
    STOP,
    /** Hit maxOutputTokens; text may end mid-sentence. */
    MAX_TOKENS,
    /** Stopped by a provider policy (safety, recitation, blocklist...). */
    BLOCKED,
    /** Any other provider reason. */
    OTHER,
    /** The stream ended without reporting a reason. */
    UNKNOWN;

    private static final Set<String> BLOCKING = Set.of(
            "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY", "LANGUAGE");

    static FinishReason parse(String raw) {
        if (raw == null || raw.isBlank() || raw.equals("FINISH_REASON_UNSPECIFIED")) {
            return UNKNOWN;
        }
        if (raw.equals("STOP")) {
            return STOP;
        }
        if (raw.equals("MAX_TOKENS")) {
            return MAX_TOKENS;
        }
        return BLOCKING.contains(raw) ? BLOCKED : OTHER;
    }
}
