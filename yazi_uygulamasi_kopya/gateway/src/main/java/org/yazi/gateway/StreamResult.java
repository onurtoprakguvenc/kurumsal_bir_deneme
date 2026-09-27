package org.yazi.gateway;

/**
 * Everything a streamed request produced. {@code text} is the raw concatenation of what the sink received;
 * sanitising and splicing happen in the caller.
 */
public record StreamResult(String text, FinishReason finishReason, Usage usage) {

    public boolean truncated() {
        return finishReason == FinishReason.MAX_TOKENS;
    }
}
