package org.yazi.gateway;

import java.time.Duration;
import java.util.Optional;

/** Any failure of a model request, classified so the UI can react (retry, re-enter key, shorten input...). */
public final class GatewayException extends Exception {

    public enum Kind {
        /** The API key was missing, invalid or not allowed to use the model. */
        AUTH,
        /** Quota or rate limit; see {@link #retryAfter()}. */
        RATE_LIMITED,
        /** The provider refused the prompt or stopped the output (safety, recitation...). */
        BLOCKED,
        /** Structured output hit the token ceiling before the JSON was complete. */
        TRUNCATED,
        /** The response could not be understood. */
        MALFORMED,
        /** Any other non-200 status. */
        HTTP,
        /** Connection, timeout or stream interruption. */
        NETWORK,
        /** The caller cancelled the request. */
        CANCELLED
    }

    private final Kind kind;
    private final int httpStatus;
    private final Duration retryAfter;

    public GatewayException(Kind kind, String message) {
        this(kind, message, 0, null, null);
    }

    public GatewayException(Kind kind, String message, Throwable cause) {
        this(kind, message, 0, null, cause);
    }

    public GatewayException(Kind kind, String message, int httpStatus, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.retryAfter = retryAfter;
    }

    public Kind kind() {
        return kind;
    }

    /** The HTTP status, or 0 when the failure did not come from a status code. */
    public int httpStatus() {
        return httpStatus;
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    /** HTTP 404 from the model endpoint: the model name does not exist (or is not available to this key). */
    public boolean isModelNotFound() {
        return kind == Kind.HTTP && httpStatus == 404;
    }

    public boolean isRetryable() {
        return kind == Kind.RATE_LIMITED || kind == Kind.NETWORK || (kind == Kind.HTTP && httpStatus >= 500);
    }
}
