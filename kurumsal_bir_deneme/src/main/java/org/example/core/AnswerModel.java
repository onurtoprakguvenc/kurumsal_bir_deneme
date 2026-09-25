package org.example.core;

import java.io.Serial;
import java.util.List;
import java.util.Objects;

/**
 * Port: a stateless, streaming text generator. Implementations never keep conversation state;
 * whatever history is needed arrives inside every {@link AnswerRequest}.
 */
public interface AnswerModel {

    String modelId();

    /** Maximum characters of retrieved source text a single request may carry for the active model. */
    int contextCharBudget();

    AnswerStats stream(AnswerRequest request, TokenSink sink) throws AnswerException;

    /** One completed exchange of the ephemeral window. */
    record Turn(String user, String model) {
        public Turn {
            Objects.requireNonNull(user, "user must not be null");
            Objects.requireNonNull(model, "model must not be null");
        }
    }

    record AnswerRequest(String systemInstruction, List<Turn> history, String userMessage) {
        public AnswerRequest {
            Objects.requireNonNull(systemInstruction, "systemInstruction must not be null");
            Objects.requireNonNull(userMessage, "userMessage must not be null");
            history = List.copyOf(history);
        }
    }

    record AnswerStats(String model, String finishReason, int promptTokens, int outputTokens,
                       long firstTokenMillis, long totalMillis) {
    }

    /** Receives answer text in arrival order. Throwing aborts the upstream stream. */
    @FunctionalInterface
    interface TokenSink {
        void accept(String delta);
    }

    final class AnswerException extends Exception {

        @Serial
        private static final long serialVersionUID = 1L;

        public enum Kind {
            NOT_CONFIGURED, QUOTA_EXCEEDED, AUTHENTICATION, BAD_REQUEST, RATE_LIMITED, UNAVAILABLE, TIMEOUT,
            BLOCKED, NETWORK, PROTOCOL, ABORTED
        }

        private final Kind kind;

        public AnswerException(Kind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = Objects.requireNonNull(kind, "kind must not be null");
        }

        public AnswerException(Kind kind, String message) {
            this(kind, message, null);
        }

        public Kind kind() {
            return kind;
        }
    }
}
