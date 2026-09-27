package org.yazi.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.yazi.gateway.GatewayException.Kind;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ResilientGatewayTest {

    record Greeting(String text) {}

    /** One scripted behaviour per call, in order. */
    @FunctionalInterface
    interface Step {
        StreamResult run(ModelCall call, TokenSink sink, CancellationToken token) throws GatewayException;
    }

    static final class SequenceGateway implements ModelGateway {
        final Deque<Step> steps = new ArrayDeque<>();
        final List<String> models = Collections.synchronizedList(new ArrayList<>());

        SequenceGateway then(Step step) {
            steps.add(step);
            return this;
        }

        @Override
        public StreamResult stream(ModelCall call, TokenSink sink, CancellationToken token) throws GatewayException {
            models.add(call.model());
            return steps.removeFirst().run(call, sink, token);
        }

        @Override
        public <T> Structured<T> structuredWithSchema(ModelCall call, Class<T> type, JsonNode schema,
                                                      CancellationToken token) throws GatewayException {
            StreamResult r = stream(call, null, token);
            return new Structured<>(type.cast(new Greeting(r.text())), r.usage());
        }
    }

    static Step ok(String... fragments) {
        return (call, sink, token) -> {
            for (String f : fragments) {
                if (sink != null) {
                    sink.accept(f);
                }
            }
            return new StreamResult(String.join("", fragments), FinishReason.STOP, new Usage(5, 2, 0, 0, 7));
        };
    }

    static Step fail(GatewayException e) {
        return (call, sink, token) -> {
            throw e;
        };
    }

    static GatewayException status(Kind kind, int http, Duration retryAfter) {
        return new GatewayException(kind, kind + " " + http, http, retryAfter, null);
    }

    static final ModelCall CALL = ModelCall.of("gemini-x-pro", "sys", "user");

    final List<Duration> slept = new ArrayList<>();
    final List<String> events = new ArrayList<>();

    final ResilientGateway.Listener recorder = new ResilientGateway.Listener() {
        @Override
        public void retrying(int attempt, int max, Duration wait, GatewayException cause) {
            events.add("retry " + attempt + "/" + max + " in " + wait.toMillis() + "ms after " + cause.kind());
        }

        @Override
        public void modelFallback(String requested, String fallback) {
            events.add("fallback " + requested + " -> " + fallback);
        }
    };

    ResilientGateway resilient(SequenceGateway delegate, ResilientGateway.Policy policy) {
        return new ResilientGateway(delegate, policy, m -> Optional.of("gemini-flash"), recorder,
                (d, t) -> slept.add(d));
    }

    static ResilientGateway.Policy noWatchdog() {
        ResilientGateway.Policy d = ResilientGateway.Policy.defaults();
        return new ResilientGateway.Policy(d.maxAttempts(), d.initialBackoff(), d.maxBackoff(), d.maxRetryAfter(), null);
    }

    // --- retry -----------------------------------------------------------------------------------

    @Test
    void successPassesThroughUntouched() throws Exception {
        SequenceGateway g = new SequenceGateway().then(ok("a", "b"));
        StringBuilder seen = new StringBuilder();
        StreamResult r = resilient(g, noWatchdog()).stream(CALL, seen::append, null);
        assertEquals("ab", r.text());
        assertEquals("ab", seen.toString());
        assertTrue(events.isEmpty());
        assertTrue(slept.isEmpty());
    }

    @Test
    void networkFailuresAreRetriedWithExponentialBackoff() throws Exception {
        SequenceGateway g = new SequenceGateway()
                .then(fail(status(Kind.NETWORK, 0, null)))
                .then(fail(status(Kind.HTTP, 503, null)))
                .then(ok("fine"));
        assertEquals("fine", resilient(g, noWatchdog()).stream(CALL, null, null).text());
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), slept);
        assertEquals(List.of("retry 2/3 in 1000ms after NETWORK", "retry 3/3 in 2000ms after HTTP"), events);
    }

    @Test
    void givesUpAfterMaxAttemptsWithTheLastError() {
        GatewayException last = status(Kind.HTTP, 502, null);
        SequenceGateway g = new SequenceGateway()
                .then(fail(status(Kind.NETWORK, 0, null)))
                .then(fail(status(Kind.NETWORK, 0, null)))
                .then(fail(last));
        GatewayException e = assertThrows(GatewayException.class,
                () -> resilient(g, noWatchdog()).stream(CALL, null, null));
        assertSame(last, e);
        assertEquals(3, g.models.size());
    }

    @Test
    void rateLimitHonoursRetryAfter() throws Exception {
        SequenceGateway g = new SequenceGateway()
                .then(fail(status(Kind.RATE_LIMITED, 429, Duration.ofSeconds(7))))
                .then(ok("ok"));
        resilient(g, noWatchdog()).stream(CALL, null, null);
        assertEquals(List.of(Duration.ofSeconds(7)), slept);
    }

    @Test
    void aSpentQuotaWithALongRetryAfterFailsImmediately() {
        GatewayException quota = status(Kind.RATE_LIMITED, 429, Duration.ofHours(3));
        SequenceGateway g = new SequenceGateway().then(fail(quota));
        assertSame(quota, assertThrows(GatewayException.class, () -> resilient(g, noWatchdog()).stream(CALL, null, null)));
        assertTrue(slept.isEmpty(), "must not freeze the editor for hours");
    }

    @Test
    void permanentFailuresAreNotRetried() {
        for (Kind kind : List.of(Kind.AUTH, Kind.BLOCKED, Kind.MALFORMED, Kind.TRUNCATED, Kind.CANCELLED)) {
            SequenceGateway g = new SequenceGateway().then(fail(status(kind, 0, null)));
            assertThrows(GatewayException.class, () -> resilient(g, noWatchdog()).stream(CALL, null, null));
            assertEquals(1, g.models.size(), kind + " was retried");
        }
        SequenceGateway badRequest = new SequenceGateway().then(fail(status(Kind.HTTP, 400, null)));
        assertThrows(GatewayException.class, () -> resilient(badRequest, noWatchdog()).stream(CALL, null, null));
        assertEquals(1, badRequest.models.size());
    }

    @Test
    void aStreamThatAlreadyShowedTextIsNeverRetried() {
        SequenceGateway g = new SequenceGateway().then((call, sink, token) -> {
            sink.accept("half a sent");
            throw status(Kind.NETWORK, 0, null);
        }).then(ok("duplicate!"));
        StringBuilder seen = new StringBuilder();
        assertThrows(GatewayException.class, () -> resilient(g, noWatchdog()).stream(CALL, seen::append, null));
        assertEquals("half a sent", seen.toString());
        assertEquals(1, g.models.size());
    }

    @Test
    void backoffIsCappedAtMaxBackoff() {
        ResilientGateway.Policy p = new ResilientGateway.Policy(10, Duration.ofSeconds(1), Duration.ofSeconds(8),
                Duration.ofSeconds(30), null);
        GatewayException net = status(Kind.NETWORK, 0, null);
        assertEquals(Duration.ofSeconds(1), p.backoff(2, net));
        assertEquals(Duration.ofSeconds(4), p.backoff(4, net));
        assertEquals(Duration.ofSeconds(8), p.backoff(5, net));
        assertEquals(Duration.ofSeconds(8), p.backoff(9, net));
    }

    @Test
    void policyRejectsNonsense() {
        assertThrows(IllegalArgumentException.class, () -> new ResilientGateway.Policy(0, Duration.ZERO,
                Duration.ZERO, Duration.ZERO, null));
    }

    // --- model fallback --------------------------------------------------------------------------

    @Test
    void missingModelFallsBackOnceAndReportsIt() throws Exception {
        SequenceGateway g = new SequenceGateway()
                .then(fail(status(Kind.HTTP, 404, null)))
                .then(ok("from flash"));
        assertEquals("from flash", resilient(g, noWatchdog()).stream(CALL, null, null).text());
        assertEquals(List.of("gemini-x-pro", "gemini-flash"), g.models);
        assertEquals(List.of("fallback gemini-x-pro -> gemini-flash"), events);
        assertTrue(slept.isEmpty(), "a fallback is immediate");
    }

    @Test
    void fallbackHappensOnlyOnce() {
        SequenceGateway g = new SequenceGateway()
                .then(fail(status(Kind.HTTP, 404, null)))
                .then(fail(status(Kind.HTTP, 404, null)));
        GatewayException e = assertThrows(GatewayException.class,
                () -> resilient(g, noWatchdog()).stream(CALL, null, null));
        assertTrue(e.isModelNotFound());
        assertEquals(2, g.models.size());
    }

    @Test
    void noFallbackWhenTheFunctionOffersNoneOrTheSameModel() {
        SequenceGateway g = new SequenceGateway().then(fail(status(Kind.HTTP, 404, null)));
        ResilientGateway same = new ResilientGateway(g, noWatchdog(), Optional::of, recorder, (d, t) -> { });
        assertThrows(GatewayException.class, () -> same.stream(CALL, null, null));
        assertEquals(1, g.models.size());
        assertTrue(events.isEmpty());
    }

    @Test
    void structuredCallsAlsoRetryAndFallBack() throws Exception {
        SequenceGateway g = new SequenceGateway()
                .then(fail(status(Kind.HTTP, 404, null)))
                .then(fail(status(Kind.RATE_LIMITED, 429, null)))
                .then(ok("merhaba"));
        Structured<Greeting> r = resilient(g, noWatchdog()).structuredWithSchema(CALL, Greeting.class, null, null);
        assertEquals("merhaba", r.value().text());
        assertEquals(List.of("gemini-x-pro", "gemini-flash", "gemini-flash"), g.models);
        assertEquals(List.of(Duration.ofSeconds(1)), slept);
    }

    // --- cancellation and the watchdog -----------------------------------------------------------

    @Test
    void cancellingDuringBackoffStopsPromptly() throws Exception {
        SequenceGateway g = new SequenceGateway()
                .then(fail(status(Kind.NETWORK, 0, null)))
                .then(ok("never"));
        ResilientGateway.Policy slow = new ResilientGateway.Policy(3, Duration.ofSeconds(30), Duration.ofSeconds(30),
                Duration.ofSeconds(30), null);
        CancellationToken token = new CancellationToken();
        CountDownLatch retrying = new CountDownLatch(1);
        ResilientGateway.Listener signal = new ResilientGateway.Listener() {
            @Override
            public void retrying(int a, int m, Duration w, GatewayException c) {
                retrying.countDown();
            }
        };
        ResilientGateway withSignal = new ResilientGateway(g, slow, m -> Optional.empty(), signal);   // real sleeper
        Thread.ofVirtual().start(() -> {
            try {
                retrying.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                // test ends anyway
            }
            token.cancel();
        });
        long start = System.nanoTime();
        GatewayException e = assertThrows(GatewayException.class, () -> withSignal.stream(CALL, null, token));
        assertEquals(Kind.CANCELLED, e.kind());
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5), "backoff was not interrupted");
        assertEquals(1, g.models.size());
    }

    @Test
    void alreadyCancelledTokenIsNotRetried() {
        CancellationToken token = new CancellationToken();
        token.cancel();
        SequenceGateway g = new SequenceGateway().then(fail(status(Kind.NETWORK, 0, null)));
        assertThrows(GatewayException.class, () -> resilient(g, noWatchdog()).stream(CALL, null, token));
        assertEquals(1, g.models.size());
    }

    /** Emits one fragment, then goes silent until cancelled (like a dead TCP connection). */
    static Step stallAfter(String fragment) {
        return (call, sink, token) -> {
            if (fragment != null) {
                sink.accept(fragment);
            }
            while (!token.isCancelled()) {
                Thread.onSpinWait();
            }
            throw new GatewayException(Kind.CANCELLED, "Request cancelled.");
        };
    }

    static ResilientGateway.Policy watchdog(long idleMillis) {
        return new ResilientGateway.Policy(3, Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofSeconds(1),
                Duration.ofMillis(idleMillis));
    }

    @Test
    void aSilentStreamIsEndedByTheWatchdogAndRetriedWhenNothingWasShown() throws Exception {
        SequenceGateway g = new SequenceGateway().then(stallAfter(null)).then(ok("recovered"));
        StreamResult r = resilient(g, watchdog(150)).stream(CALL, null, null);
        assertEquals("recovered", r.text());
        assertTrue(events.getFirst().endsWith("after NETWORK"), events.toString());
    }

    @Test
    void aStreamThatStallsMidwayReportsANetworkErrorInsteadOfHanging() {
        SequenceGateway g = new SequenceGateway().then(stallAfter("partial "));
        long start = System.nanoTime();
        GatewayException e = assertThrows(GatewayException.class,
                () -> resilient(g, watchdog(150)).stream(CALL, null, null));
        assertEquals(Kind.NETWORK, e.kind());
        assertTrue(e.getMessage().contains("stopped responding"), e.getMessage());
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5));
    }

    @Test
    void userCancellationIsNotMistakenForAStall() {
        CancellationToken token = new CancellationToken();
        SequenceGateway g = new SequenceGateway().then(stallAfter("x"));
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                // cancel anyway
            }
            token.cancel();
        });
        GatewayException e = assertThrows(GatewayException.class,
                () -> resilient(g, watchdog(10_000)).stream(CALL, null, token));
        assertEquals(Kind.CANCELLED, e.kind());
    }

    @Test
    void modelNotFoundIsOnly404() {
        assertTrue(status(Kind.HTTP, 404, null).isModelNotFound());
        assertFalse(status(Kind.HTTP, 400, null).isModelNotFound());
        assertFalse(status(Kind.AUTH, 404, null).isModelNotFound());
        assertEquals("other", CALL.withModel("other").model());
        assertEquals(CALL.user(), CALL.withModel("other").user());
    }
}
