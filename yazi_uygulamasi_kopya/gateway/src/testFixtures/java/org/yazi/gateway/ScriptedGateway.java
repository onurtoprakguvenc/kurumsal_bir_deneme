package org.yazi.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Offline {@link ModelGateway} for tests: records every call (and the schema of structured calls) and replays a
 * scripted reply. Streamed calls can stall after the first fragment until released or cancelled, to exercise
 * cancellation and live preview. Structured calls parse the scripted fragments, joined, as JSON.
 */
public final class ScriptedGateway implements ModelGateway {

    /** One recorded request; {@code schema} is null for streamed text calls. */
    public record Recorded(ModelCall call, JsonNode schema) {}

    private static final ObjectMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final List<Recorded> recorded = Collections.synchronizedList(new ArrayList<>());
    private volatile List<String> fragments = List.of("ok.");
    private volatile FinishReason finishReason = FinishReason.STOP;
    private volatile Usage usage = new Usage(100, 10, 0, 0, 110);
    private volatile GatewayException failure;
    private volatile CountDownLatch stall;

    public ScriptedGateway reply(String... parts) {
        this.fragments = List.of(parts);
        return this;
    }

    public ScriptedGateway finishWith(FinishReason reason) {
        this.finishReason = reason;
        return this;
    }

    public ScriptedGateway usage(Usage value) {
        this.usage = value;
        return this;
    }

    public ScriptedGateway failWith(GatewayException e) {
        this.failure = e;
        return this;
    }

    /** After the first fragment, wait until {@code release} opens or the request is cancelled. */
    public ScriptedGateway stallAfterFirstFragment(CountDownLatch release) {
        this.stall = release;
        return this;
    }

    public List<Recorded> recorded() {
        synchronized (recorded) {
            return List.copyOf(recorded);
        }
    }

    public List<ModelCall> calls() {
        return recorded().stream().map(Recorded::call).toList();
    }

    public ModelCall lastCall() {
        List<Recorded> all = recorded();
        return all.isEmpty() ? null : all.get(all.size() - 1).call();
    }

    @Override
    public StreamResult stream(ModelCall call, TokenSink sink, CancellationToken cancellation) throws GatewayException {
        recorded.add(new Recorded(call, null));
        CancellationToken token = (cancellation == null) ? CancellationToken.none() : cancellation;
        if (failure != null) {
            throw failure;
        }
        StringBuilder text = new StringBuilder();
        boolean first = true;
        for (String fragment : fragments) {
            checkCancelled(token);
            text.append(fragment);
            if (sink != null) {
                sink.accept(fragment);
            }
            if (first && stall != null) {
                awaitReleaseOrCancel(stall, token);
            }
            first = false;
        }
        checkCancelled(token);
        return new StreamResult(text.toString(), finishReason, usage);
    }

    @Override
    public <T> Structured<T> structuredWithSchema(ModelCall call, Class<T> type, JsonNode responseSchema,
                                                  CancellationToken cancellation) throws GatewayException {
        recorded.add(new Recorded(call, responseSchema));
        if (failure != null) {
            throw failure;
        }
        if (cancellation != null && cancellation.isCancelled()) {
            throw new GatewayException(GatewayException.Kind.CANCELLED, "Request cancelled.");
        }
        if (finishReason == FinishReason.MAX_TOKENS) {
            throw new GatewayException(GatewayException.Kind.TRUNCATED, "scripted truncation");
        }
        try {
            return new Structured<>(JSON.readValue(String.join("", fragments), type), usage);
        } catch (JsonProcessingException e) {
            throw new GatewayException(GatewayException.Kind.MALFORMED, e.getOriginalMessage(), e);
        }
    }

    private static void awaitReleaseOrCancel(CountDownLatch release, CancellationToken token) throws GatewayException {
        try {
            while (!release.await(10, TimeUnit.MILLISECONDS)) {
                checkCancelled(token);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException(GatewayException.Kind.CANCELLED, "interrupted");
        }
    }

    private static void checkCancelled(CancellationToken token) throws GatewayException {
        if (token.isCancelled()) {
            throw new GatewayException(GatewayException.Kind.CANCELLED, "Request cancelled.");
        }
    }
}
