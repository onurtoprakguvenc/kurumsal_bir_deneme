package org.yazi.motion.domain.exception;

import java.io.Serial;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Base of the pipeline error taxonomy. Every failure carries a stable error code, a timestamp and details. */
public abstract class PromptEngineException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final long timestamp;
    private final transient Map<String, Object> details;

    protected PromptEngineException(String message) {
        this(message, null, null);
    }

    protected PromptEngineException(String message, Map<String, ?> details) {
        this(message, details, null);
    }

    protected PromptEngineException(String message, Map<String, ?> details, Throwable cause) {
        super(message, cause);
        this.timestamp = System.currentTimeMillis();
        this.details = details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(details));
    }

    public abstract String errorCode();

    public long timestamp() {
        return timestamp;
    }

    public Map<String, Object> details() {
        return details;
    }

    public String name() {
        return getClass().getSimpleName();
    }
}
