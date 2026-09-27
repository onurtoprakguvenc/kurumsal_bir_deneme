package org.yazi.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import org.yazi.gateway.GatewayException.Kind;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Wraps any {@link ModelGateway} with the recovery a live API needs. Each call is still one independent request;
 * nothing is remembered between calls.
 *
 * <ul>
 *   <li><b>Retry with backoff</b> for {@linkplain GatewayException#isRetryable() transient failures} (rate limits,
 *       network errors, HTTP 5xx): exponential backoff, or the server's {@code Retry-After} when it sends one. A
 *       {@code Retry-After} longer than {@link Policy#maxRetryAfter()} (a spent daily quota) fails at once instead
 *       of freezing the editor.</li>
 *   <li><b>A stream is only retried before its first fragment.</b> After that the caller has already shown text,
 *       and a retry would duplicate it; the error is reported instead.</li>
 *   <li><b>Model fallback.</b> When the model does not exist (HTTP 404), the call is repeated once with the model
 *       the fallback function names, and the listener is told so the writer can fix the setting.</li>
 *   <li><b>Stall watchdog.</b> {@link GeminiGateway} only times out while waiting for response headers; a stream
 *       that goes silent afterwards would hang forever. With {@link Policy#streamIdleTimeout()} set, a stream with
 *       no fragment for that long is ended and reported as a {@link Kind#NETWORK} failure.</li>
 *   <li>Every wait is cancellable through the caller's {@link CancellationToken}.</li>
 * </ul>
 *
 * <p>Thread-safe; the listener is called on the request thread.</p>
 */
public final class ResilientGateway implements ModelGateway {

    /** Recovery settings. {@code streamIdleTimeout} null disables the watchdog. */
    public record Policy(int maxAttempts, Duration initialBackoff, Duration maxBackoff, Duration maxRetryAfter,
                         Duration streamIdleTimeout) {

        public Policy {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be at least 1");
            }
            Objects.requireNonNull(initialBackoff, "initialBackoff");
            Objects.requireNonNull(maxBackoff, "maxBackoff");
            Objects.requireNonNull(maxRetryAfter, "maxRetryAfter");
        }

        /** Three attempts, 1 s → 2 s backoff (capped at 8 s), Retry-After up to 30 s, 120 s stream idle limit. */
        public static Policy defaults() {
            return new Policy(3, Duration.ofSeconds(1), Duration.ofSeconds(8), Duration.ofSeconds(30),
                    Duration.ofSeconds(120));
        }

        /** Backoff before attempt {@code nextAttempt} (2, 3, ...): the server's hint, else exponential. */
        Duration backoff(int nextAttempt, GatewayException cause) {
            Optional<Duration> hint = cause.retryAfter();
            if (hint.isPresent()) {
                return hint.get();
            }
            long millis = initialBackoff.toMillis() << Math.min(20, Math.max(0, nextAttempt - 2));
            return Duration.ofMillis(Math.min(millis, maxBackoff.toMillis()));
        }
    }

    /** What the recovery layer is doing, for a status line. */
    public interface Listener {
        /** About to wait {@code wait}, then make attempt {@code attempt} of {@code maxAttempts}. */
        default void retrying(int attempt, int maxAttempts, Duration wait, GatewayException cause) {}

        /** {@code requested} does not exist; the call is repeated with {@code fallback}. */
        default void modelFallback(String requested, String fallback) {}

        Listener NONE = new Listener() {};
    }

    /** Waits, returning early with {@link Kind#CANCELLED} when the token fires. Replaceable in tests. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration, CancellationToken cancellation) throws GatewayException;
    }

    private final ModelGateway delegate;
    private final Policy policy;
    private final Function<String, Optional<String>> fallbackModel;
    private final Listener listener;
    private final Sleeper sleeper;

    public ResilientGateway(ModelGateway delegate, Policy policy, Function<String, Optional<String>> fallbackModel,
                            Listener listener) {
        this(delegate, policy, fallbackModel, listener, ResilientGateway::sleepCancellably);
    }

    public ResilientGateway(ModelGateway delegate, Policy policy, Function<String, Optional<String>> fallbackModel,
                            Listener listener, Sleeper sleeper) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.fallbackModel = (fallbackModel == null) ? m -> Optional.empty() : fallbackModel;
        this.listener = (listener == null) ? Listener.NONE : listener;
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    @Override
    public StreamResult stream(ModelCall call, TokenSink sink, CancellationToken cancellation) throws GatewayException {
        TokenSink target = (sink == null) ? TokenSink.DISCARD : sink;
        CancellationToken token = (cancellation == null) ? CancellationToken.none() : cancellation;
        ModelCall current = call;
        boolean fellBack = false;
        int attempt = 1;
        while (true) {
            ActivitySink activity = new ActivitySink(target);
            try {
                return streamWatched(current, activity, token);
            } catch (GatewayException e) {
                if (activity.emitted()) {
                    throw e;   // text already reached the caller; a retry would duplicate it
                }
                Optional<String> fallback = fellBack ? Optional.empty() : fallbackFor(current, e);
                if (fallback.isPresent()) {
                    listener.modelFallback(current.model(), fallback.get());
                    current = current.withModel(fallback.get());
                    fellBack = true;
                    continue;
                }
                waitBeforeRetry(++attempt, e, token);
            }
        }
    }

    @Override
    public <T> Structured<T> structuredWithSchema(ModelCall call, Class<T> type, JsonNode responseSchema,
                                                  CancellationToken cancellation) throws GatewayException {
        CancellationToken token = (cancellation == null) ? CancellationToken.none() : cancellation;
        ModelCall current = call;
        boolean fellBack = false;
        int attempt = 1;
        while (true) {
            try {
                return delegate.structuredWithSchema(current, type, responseSchema, token);
            } catch (GatewayException e) {
                Optional<String> fallback = fellBack ? Optional.empty() : fallbackFor(current, e);
                if (fallback.isPresent()) {
                    listener.modelFallback(current.model(), fallback.get());
                    current = current.withModel(fallback.get());
                    fellBack = true;
                    continue;
                }
                waitBeforeRetry(++attempt, e, token);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------

    private Optional<String> fallbackFor(ModelCall call, GatewayException e) {
        if (!e.isModelNotFound()) {
            return Optional.empty();
        }
        return fallbackModel.apply(call.model()).filter(m -> !m.isBlank() && !m.equals(call.model()));
    }

    /** Throws {@code cause} unless another attempt is allowed; otherwise waits for it. */
    private void waitBeforeRetry(int nextAttempt, GatewayException cause, CancellationToken token)
            throws GatewayException {
        if (!cause.isRetryable() || nextAttempt > policy.maxAttempts() || token.isCancelled()) {
            throw cause;
        }
        if (cause.retryAfter().filter(d -> d.compareTo(policy.maxRetryAfter()) > 0).isPresent()) {
            throw cause;
        }
        Duration wait = policy.backoff(nextAttempt, cause);
        listener.retrying(nextAttempt, policy.maxAttempts(), wait, cause);
        sleeper.sleep(wait, token);
    }

    private StreamResult streamWatched(ModelCall call, ActivitySink sink, CancellationToken parent)
            throws GatewayException {
        Duration idle = policy.streamIdleTimeout();
        if (idle == null) {
            return delegate.stream(call, sink, parent);
        }
        CancellationToken child = new CancellationToken();
        AtomicBoolean stalled = new AtomicBoolean();
        try (CancellationToken.Registration ignored = parent.onCancel(child::cancel)) {
            Thread watchdog = Thread.ofVirtual().name("yazi-stream-watchdog").start(() -> {
                long limit = idle.toNanos();
                try {
                    while (!child.isCancelled()) {
                        long quiet = System.nanoTime() - sink.lastActivity();
                        if (quiet >= limit) {
                            stalled.set(true);
                            child.cancel();
                            return;
                        }
                        TimeUnit.NANOSECONDS.sleep(Math.min(limit - quiet, TimeUnit.MILLISECONDS.toNanos(250)));
                    }
                } catch (InterruptedException done) {
                    // the request finished first
                }
            });
            try {
                return delegate.stream(call, sink, child);
            } catch (GatewayException e) {
                if (stalled.get() && !parent.isCancelled()) {
                    throw new GatewayException(Kind.NETWORK, "The model stopped responding (no data for "
                            + idle.toSeconds() + " s).", e);
                }
                throw e;
            } finally {
                watchdog.interrupt();
            }
        }
    }

    /** Forwards fragments and remembers whether (and when) any arrived. */
    private static final class ActivitySink implements TokenSink {
        private final TokenSink target;
        private volatile long lastActivity = System.nanoTime();
        private volatile boolean emitted;

        ActivitySink(TokenSink target) {
            this.target = target;
        }

        @Override
        public void accept(String fragment) {
            lastActivity = System.nanoTime();
            if (!fragment.isEmpty()) {
                emitted = true;
            }
            target.accept(fragment);
        }

        long lastActivity() {
            return lastActivity;
        }

        boolean emitted() {
            return emitted;
        }
    }

    private static void sleepCancellably(Duration duration, CancellationToken cancellation) throws GatewayException {
        CountDownLatch cancelled = new CountDownLatch(1);
        try (CancellationToken.Registration ignored = cancellation.onCancel(cancelled::countDown)) {
            if (cancelled.await(duration.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new GatewayException(Kind.CANCELLED, "Request cancelled.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException(Kind.CANCELLED, "Request cancelled.");
        }
    }
}
