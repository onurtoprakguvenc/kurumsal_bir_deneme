package org.yazi.motion.domain.exception;

import java.io.Serial;
import java.util.Map;

/** A stage was invoked from a state that does not allow it. */
public class IllegalStateTransitionException extends PromptEngineException {
    @Serial
    private static final long serialVersionUID = 1L;

    public static final String ERROR_CODE = "ERR_ILLEGAL_STATE_TRANSITION";

    public IllegalStateTransitionException(String message) {
        super(message);
    }

    public IllegalStateTransitionException(String message, Map<String, ?> details) {
        super(message, details);
    }

    public IllegalStateTransitionException(String message, Map<String, ?> details, Throwable cause) {
        super(message, details, cause);
    }

    @Override
    public String errorCode() {
        return ERROR_CODE;
    }
}
