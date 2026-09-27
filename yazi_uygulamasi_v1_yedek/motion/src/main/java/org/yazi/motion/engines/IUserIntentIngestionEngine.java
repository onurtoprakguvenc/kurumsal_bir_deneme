package org.yazi.motion.engines;

import org.yazi.motion.domain.RawUserPromptInput;

import java.util.concurrent.CompletableFuture;

public interface IUserIntentIngestionEngine {
    /**
     * Ingests, normalizes, and verifies that the user input strictly describes a video asset.
     * The returned future completes exceptionally with:
     * {@link org.yazi.motion.domain.exception.InvalidPayloadException} if structural fields are missing or corrupted,
     * {@link org.yazi.motion.domain.exception.OutOfScopeDomainException} if the prompt requests non-video generation,
     * {@link org.yazi.motion.domain.exception.InvalidDurationException} if duration violates 1s &lt;= t &lt;= 60s.
     */
    CompletableFuture<ParsedIntentResult> ingestAndValidate(RawUserPromptInput input);
}
