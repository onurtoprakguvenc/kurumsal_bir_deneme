package org.yazi.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import org.yazi.gateway.schema.SchemaDeriver;
import org.yazi.gateway.schema.SchemaOptions;

/**
 * The single way the application talks to a model. Implementations are stateless and thread-safe; each call is
 * one system instruction plus one user turn ({@link ModelCall}).
 */
public interface ModelGateway {

    /**
     * Streams plain text to {@code sink} as it arrives.
     *
     * <p>A MAX_TOKENS stop is returned normally ({@link StreamResult#truncated()}); policy stops (safety etc.)
     * throw {@link GatewayException.Kind#BLOCKED} even if some text was already streamed.</p>
     *
     * @param cancellation may be null
     */
    StreamResult stream(ModelCall call, TokenSink sink, CancellationToken cancellation) throws GatewayException;

    /**
     * Requests JSON constrained by {@code responseSchema} and parses it into {@code type}. Use this when the
     * schema is authored by hand (for example with per-field descriptions that steer the model).
     * A MAX_TOKENS stop throws {@link GatewayException.Kind#TRUNCATED}, because partial JSON is useless.
     *
     * @param cancellation may be null
     */
    <T> Structured<T> structuredWithSchema(ModelCall call, Class<T> type, JsonNode responseSchema,
                                           CancellationToken cancellation) throws GatewayException;

    /**
     * Like {@link #structuredWithSchema}, with the schema derived from the record type {@code type}.
     *
     * @param options      may be null
     * @param cancellation may be null
     */
    default <T> Structured<T> structured(ModelCall call, Class<T> type, SchemaOptions options,
                                         CancellationToken cancellation) throws GatewayException {
        return structuredWithSchema(call, type, SchemaDeriver.derive(type, options), cancellation);
    }
}
