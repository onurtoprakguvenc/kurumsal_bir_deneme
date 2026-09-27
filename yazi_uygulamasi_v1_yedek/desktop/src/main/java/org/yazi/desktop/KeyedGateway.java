package org.yazi.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import org.yazi.gateway.CancellationToken;
import org.yazi.gateway.GatewayException;
import org.yazi.gateway.GeminiGateway;
import org.yazi.gateway.ModelCall;
import org.yazi.gateway.ModelGateway;
import org.yazi.gateway.StreamResult;
import org.yazi.gateway.Structured;
import org.yazi.gateway.TokenSink;

/**
 * A gateway whose API key can be set after start-up. Without a key every call fails with
 * {@link GatewayException.Kind#AUTH}, and the editor keeps working as a plain editor.
 */
final class KeyedGateway implements ModelGateway {

    private volatile GeminiGateway delegate;

    KeyedGateway(String apiKey) {
        setKey(apiKey);
    }

    void setKey(String apiKey) {
        delegate = (apiKey == null || apiKey.isBlank()) ? null : new GeminiGateway(apiKey);
    }

    boolean hasKey() {
        return delegate != null;
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

    private GeminiGateway current() throws GatewayException {
        GeminiGateway g = delegate;
        if (g == null) {
            throw new GatewayException(GatewayException.Kind.AUTH, "No API key set.");
        }
        return g;
    }
}
