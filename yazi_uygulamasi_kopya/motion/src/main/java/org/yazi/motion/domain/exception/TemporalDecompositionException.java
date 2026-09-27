package org.yazi.motion.domain.exception;

import java.io.Serial;
import java.util.Map;

/** Decomposition failed to account for the total duration. */
public class TemporalDecompositionException extends PromptEngineException {
    @Serial
    private static final long serialVersionUID = 1L;

    public static final String ERROR_CODE = "ERR_TEMPORAL_DECOMPOSITION_FAILED";

    public TemporalDecompositionException(String message) {
        super(message);
    }

    public TemporalDecompositionException(String message, Map<String, ?> details) {
        super(message, details);
    }

    public TemporalDecompositionException(String message, Map<String, ?> details, Throwable cause) {
        super(message, details, cause);
    }

    @Override
    public String errorCode() {
        return ERROR_CODE;
    }
}
