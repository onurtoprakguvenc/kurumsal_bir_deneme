package org.yazi.motion.domain.exception;

import java.io.Serial;
import java.util.Map;

/** Structural fields are missing or corrupted. */
public class InvalidPayloadException extends PromptEngineException {
    @Serial
    private static final long serialVersionUID = 1L;

    public static final String ERROR_CODE = "ERR_INVALID_PAYLOAD";

    public InvalidPayloadException(String message) {
        super(message);
    }

    public InvalidPayloadException(String message, Map<String, ?> details) {
        super(message, details);
    }

    public InvalidPayloadException(String message, Map<String, ?> details, Throwable cause) {
        super(message, details, cause);
    }

    @Override
    public String errorCode() {
        return ERROR_CODE;
    }
}
