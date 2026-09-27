package org.yazi.gateway;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Token accounting reported by the provider for one request. This is how token efficiency is verified, not
 * estimated.
 */
public record Usage(int promptTokens, int outputTokens, int thinkingTokens, int cachedTokens, int totalTokens) {

    public static final Usage EMPTY = new Usage(0, 0, 0, 0, 0);

    static Usage from(JsonNode meta) {
        return new Usage(
                meta.path("promptTokenCount").asInt(0),
                meta.path("candidatesTokenCount").asInt(0),
                meta.path("thoughtsTokenCount").asInt(0),
                meta.path("cachedContentTokenCount").asInt(0),
                meta.path("totalTokenCount").asInt(0));
    }
}
