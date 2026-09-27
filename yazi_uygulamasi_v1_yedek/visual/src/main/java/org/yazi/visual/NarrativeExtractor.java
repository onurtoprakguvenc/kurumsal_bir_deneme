package org.yazi.visual;

import org.yazi.gateway.CancellationToken;
import org.yazi.gateway.GatewayException;
import org.yazi.gateway.ModelCall;
import org.yazi.gateway.ModelGateway;
import org.yazi.gateway.StreamResult;
import org.yazi.gateway.TokenSink;
import org.yazi.gateway.Usage;

import java.util.List;

/**
 * Collapses multi-event narrative prose into a single staged keyframe before contract extraction.
 *
 * <p>Ported from image_generate_prompt_improve. The system instruction, temperature (0.4) and output ceiling
 * (2000) are unchanged; only the transport moved to the shared {@link ModelGateway}, and the model is always the
 * one the request names.</p>
 */
public final class NarrativeExtractor {

    static final int MAX_OUTPUT_TOKENS = 2000;
    static final double TEMPERATURE = 0.4;

    static final String SYSTEM_INSTRUCTION =
            "Isolate the single pivotal moment of highest physical tension and mechanical contact from the narrative. "
                    + "Translate internal states entirely into observable physical postures, contact displacements, and dynamic forces. "
                    + "Output a single objective paragraph of physical staging. Emit raw descriptive text directly.";

    private final ModelGateway gateway;

    public NarrativeExtractor(ModelGateway gateway) {
        this.gateway = gateway;
    }

    /** The isolated keyframe and what the call cost. */
    public record Keyframe(String text, Usage usage) {}

    public Keyframe extractKeyframe(String narrativeSequence, String modelEndpoint, CancellationToken cancellation)
            throws GatewayException {
        ModelCall call = new ModelCall(modelEndpoint, SYSTEM_INSTRUCTION, narrativeSequence,
                TEMPERATURE, MAX_OUTPUT_TOKENS, null, List.of());
        StreamResult result = gateway.stream(call, TokenSink.DISCARD, cancellation);

        if (result.truncated()) {
            throw new GatewayException(GatewayException.Kind.TRUNCATED,
                    "Keyframe extraction truncated at maxOutputTokens (" + MAX_OUTPUT_TOKENS + ").");
        }
        String text = result.text().trim();
        if (text.isEmpty()) {
            throw new GatewayException(GatewayException.Kind.MALFORMED, "Keyframe extraction returned empty text.");
        }
        return new Keyframe(text, result.usage());
    }
}
