package org.example.core;

import java.io.Serial;
import java.util.Objects;

/**
 * Classified ingestion failure. Carries no transport or UI concerns.
 */
public final class IngestionException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    public enum Reason {
        NOT_FOUND,
        NOT_A_FILE,
        ACCESS_DENIED,
        TOO_LARGE,
        UNSUPPORTED,
        ENCRYPTED,
        CORRUPTED,
        EMPTY,
        CHANGED_DURING_READ,
        MEMORY_PRESSURE,
        IO_ERROR
    }

    private final Reason reason;

    public IngestionException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    public IngestionException(Reason reason, String message) {
        this(reason, message, null);
    }

    public Reason reason() {
        return reason;
    }
}
