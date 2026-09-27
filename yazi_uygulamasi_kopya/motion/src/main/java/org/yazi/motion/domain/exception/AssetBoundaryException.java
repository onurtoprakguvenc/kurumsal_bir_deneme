package org.yazi.motion.domain.exception;

import java.io.Serial;
import java.util.Map;

/** An asset interval cannot be placed inside [0, duration]. */
public class AssetBoundaryException extends PromptEngineException {
    @Serial
    private static final long serialVersionUID = 1L;

    public static final String ERROR_CODE = "ERR_ASSET_TEMPORAL_OUT_OF_BOUNDS";

    public AssetBoundaryException(String message) {
        super(message);
    }

    public AssetBoundaryException(String message, Map<String, ?> details) {
        super(message, details);
    }

    public AssetBoundaryException(String message, Map<String, ?> details, Throwable cause) {
        super(message, details, cause);
    }

    @Override
    public String errorCode() {
        return ERROR_CODE;
    }
}
