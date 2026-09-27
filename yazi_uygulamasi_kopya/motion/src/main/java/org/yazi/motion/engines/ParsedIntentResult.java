package org.yazi.motion.engines;

import org.yazi.motion.domain.AspectRatio;
import org.yazi.motion.domain.ImperfectionLevel;
import org.yazi.motion.domain.ReferenceAssetBinding;
import org.yazi.motion.domain.StylePreferences;

import java.util.List;

public record ParsedIntentResult(
        String requestId,
        String sanitizedDescription,
        double targetDurationSec,
        AspectRatio aspectRatio,
        List<ReferenceAssetBinding> rawAssets,
        StylePreferences explicitStylePreferences,
        boolean isVideoDomain,
        List<String> suppressDetailAreas,
        ImperfectionLevel imperfectionLevel,
        String specificImperfections) {

    public ParsedIntentResult {
        rawAssets = rawAssets == null ? List.of() : List.copyOf(rawAssets);
        explicitStylePreferences = explicitStylePreferences == null ? StylePreferences.none() : explicitStylePreferences;
        suppressDetailAreas = suppressDetailAreas == null ? List.of() : List.copyOf(suppressDetailAreas);
        imperfectionLevel = imperfectionLevel == null ? ImperfectionLevel.OFF : imperfectionLevel;
        specificImperfections = specificImperfections == null ? "" : specificImperfections;
    }

    public ParsedIntentResult(String requestId, String sanitizedDescription, double targetDurationSec, AspectRatio aspectRatio,
                              List<ReferenceAssetBinding> rawAssets, StylePreferences explicitStylePreferences,
                              boolean isVideoDomain) {
        this(requestId, sanitizedDescription, targetDurationSec, aspectRatio, rawAssets, explicitStylePreferences,
                isVideoDomain, List.of(), ImperfectionLevel.OFF, "");
    }

    public PromptOptions promptOptions() {
        return new PromptOptions(sanitizedDescription, suppressDetailAreas, imperfectionLevel, specificImperfections);
    }
}
