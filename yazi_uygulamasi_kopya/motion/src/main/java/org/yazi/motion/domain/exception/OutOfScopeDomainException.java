package org.yazi.motion.domain.exception;

import java.io.Serial;
import java.util.Map;

/** The prompt requests non-video generation (text, code, prose, audio). */
public class OutOfScopeDomainException extends PromptEngineException {
    @Serial
    private static final long serialVersionUID = 1L;

    public static final String ERROR_CODE = "ERR_OUT_OF_SCOPE_DOMAIN";

    public OutOfScopeDomainException(String message) {
        super(message);
    }

    public OutOfScopeDomainException(String message, Map<String, ?> details) {
        super(message, details);
    }

    public OutOfScopeDomainException(String message, Map<String, ?> details, Throwable cause) {
        super(message, details, cause);
    }

    @Override
    public String errorCode() {
        return ERROR_CODE;
    }
}
