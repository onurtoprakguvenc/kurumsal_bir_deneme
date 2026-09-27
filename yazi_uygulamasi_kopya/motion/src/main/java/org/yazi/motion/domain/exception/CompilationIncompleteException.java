package org.yazi.motion.domain.exception;

import java.io.Serial;
import java.util.Map;

/** Required prompt attributes are missing or invalid at compile time. */
public class CompilationIncompleteException extends PromptEngineException {
    @Serial
    private static final long serialVersionUID = 1L;

    public static final String ERROR_CODE = "ERR_COMPILATION_FAILED";

    public CompilationIncompleteException(String message) {
        super(message);
    }

    public CompilationIncompleteException(String message, Map<String, ?> details) {
        super(message, details);
    }

    public CompilationIncompleteException(String message, Map<String, ?> details, Throwable cause) {
        super(message, details, cause);
    }

    @Override
    public String errorCode() {
        return ERROR_CODE;
    }
}
