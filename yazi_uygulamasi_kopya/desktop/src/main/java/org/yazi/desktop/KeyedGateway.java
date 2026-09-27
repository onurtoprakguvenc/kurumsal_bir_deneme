package org.yazi.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import org.yazi.gateway.CancellationToken;
import org.yazi.gateway.GatewayException;
import org.yazi.gateway.GeminiGateway;
import org.yazi.gateway.ModelCall;
import org.yazi.gateway.ModelGateway;
import org.yazi.gateway.ResilientGateway;
import org.yazi.gateway.StreamResult;
import org.yazi.gateway.Structured;
import org.yazi.gateway.TokenSink;
import org.yazi.prose.ModelCatalog;

import java.time.Duration;
import java.util.Optional;

/**
 * A gateway whose API key can be set after start-up. Without a key every call fails with
 * {@link GatewayException.Kind#AUTH}, and the editor keeps working as a plain editor.
 *
 * <p>With a key, calls go through a {@link ResilientGateway}: transient failures are retried with backoff, a
 * silent stream is ended by a watchdog, and a model that does not exist falls back to
 * {@link ModelCatalog#DEFAULT_FLASH}. What that layer does is reported to the {@linkplain #setListener listener}
 * (the status bar).</p>
 */
final class KeyedGateway implements ModelGateway {

    private volatile ModelGateway delegate;
    private volatile ResilientGateway.Listener listener = ResilientGateway.Listener.NONE;

    /** Forwards to whatever listener is current, so the listener can be attached after a key was set. */
    private final ResilientGateway.Listener forwarding = new ResilientGateway.Listener() {
        @Override
        public void retrying(int attempt, int maxAttempts, Duration wait, GatewayException cause) {
            listener.retrying(attempt, maxAttempts, wait, cause);
        }

        @Override
        public void modelFallback(String requested, String fallback) {
            listener.modelFallback(requested, fallback);
        }
    };

    KeyedGateway(String apiKey) {
        setKey(apiKey);
    }

    void setKey(String apiKey) {
        delegate = (apiKey == null || apiKey.isBlank()) ? null
                : new ResilientGateway(new GeminiGateway(apiKey), ResilientGateway.Policy.defaults(),
                KeyedGateway::fallbackModel, forwarding);
    }

    void setListener(ResilientGateway.Listener value) {
        listener = (value == null) ? ResilientGateway.Listener.NONE : value;
    }

    boolean hasKey() {
        return delegate != null;
    }

    /** Any model that does not exist falls back to the default Flash model (once per call). */
    static Optional<String> fallbackModel(String model) {
        return ModelCatalog.DEFAULT_FLASH.equals(model) ? Optional.empty() : Optional.of(ModelCatalog.DEFAULT_FLASH);
    }

    @Override
    public StreamResult stream(ModelCall call, TokenSink sink, CancellationToken cancellation) throws GatewayException {
        return current().stream(call, sink, cancellation);
    }

    @Override
    public <T> Structured<T> structuredWithSchema(ModelCall call, Class<T> type, JsonNode responseSchema,
                                                  CancellationToken cancellation) throws GatewayException {
        return current().structuredWithSchema(call, type, responseSchema, cancellation);
    }

    private ModelGateway current() throws GatewayException {
        ModelGateway g = delegate;
        if (g == null) {
            throw new GatewayException(GatewayException.Kind.AUTH, "No API key set.");
        }
        return g;
    }
}
