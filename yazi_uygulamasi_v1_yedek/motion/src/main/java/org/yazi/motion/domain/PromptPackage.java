package org.yazi.motion.domain;

import java.util.List;

import static org.yazi.motion.domain.DomainValidation.*;

public record PromptPackage(
        String requestId,
        String schemaVersion,
        double targetDuration,
        AspectRatio aspectRatio,
        VisualStyleMatrix styleMatrix,
        List<TemporalSegment> temporalSegments,
        List<ReferenceAssetBinding> boundAssets,
        CompiledPrompts compiledPrompts,
        Metadata metadata) {

    public PromptPackage {
        requireText(requestId, "request_id");
        requireText(schemaVersion, "schema_version");
        require(aspectRatio, "aspect_ratio");
        require(styleMatrix, "style_matrix");
        temporalSegments = immutable(temporalSegments);
        boundAssets = immutable(boundAssets);
        require(compiledPrompts, "compiled_prompts");
        require(metadata, "metadata");
    }

    public record CompiledPrompts(String positivePromptSummary, String negativePromptSummary, String temporalBreakdownText) {
        public CompiledPrompts {
            requireText(positivePromptSummary, "positive_prompt_summary");
            requireText(negativePromptSummary, "negative_prompt_summary");
            requireText(temporalBreakdownText, "temporal_breakdown_text");
        }
    }

    /** @param compiledAt ISO-8601 instant */
    public record Metadata(String compiledAt, String engineVersion) {
        public Metadata {
            requireText(compiledAt, "compiled_at");
            requireText(engineVersion, "engine_version");
        }
    }
}
