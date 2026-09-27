package org.yazi.motion.domain.exception;

import java.io.Serial;
import java.util.Map;

/** The continuity invariant (start_i = end_{i-1}) is broken. */
public class TemporalGapException extends PromptEngineException {
    @Serial
    private static final long serialVersionUID = 1L;

    public static final String ERROR_CODE = "ERR_TEMPORAL_GAP_DETECTED";

    public TemporalGapException(String message) {
        super(message);
    }

    public TemporalGapException(String message, Map<String, ?> details) {
        super(message, details);
    }

    public TemporalGapException(String message, Map<String, ?> details, Throwable cause) {
        super(message, details, cause);
    }

    @Override
    public String errorCode() {
        return ERROR_CODE;
    }
}
