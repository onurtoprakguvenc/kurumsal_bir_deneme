package org.yazi.motion.domain.exception;

import java.io.Serial;
import java.util.Map;

/** target_duration_sec violates 1s &lt;= t &lt;= 60s. */
public class InvalidDurationException extends PromptEngineException {
    @Serial
    private static final long serialVersionUID = 1L;

    public static final String ERROR_CODE = "ERR_INVALID_DURATION";

    public InvalidDurationException(String message) {
        super(message);
    }

    public InvalidDurationException(String message, Map<String, ?> details) {
        super(message, details);
    }

    public InvalidDurationException(String message, Map<String, ?> details, Throwable cause) {
        super(message, details, cause);
    }

    @Override
    public String errorCode() {
        return ERROR_CODE;
    }
}
