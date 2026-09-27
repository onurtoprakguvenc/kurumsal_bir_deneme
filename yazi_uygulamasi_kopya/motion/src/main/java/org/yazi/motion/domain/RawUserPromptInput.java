package org.yazi.motion.domain;

import java.util.List;

/**
 * Untrusted request payload; validated by the ingestion engine, never in this constructor.
 *
 * @param suppressDetailAreas   optional, e.g. "sky, ground"; each area becomes detail-suppression phrases in the negative
 *                              prompt and replaces the automatic focus-budget decision
 * @param imperfectionLevel     optional; null means OFF
 * @param specificImperfections optional, used verbatim in the positive prompt when the level is not OFF
 */
public record RawUserPromptInput(
        String requestId,
        String userDescription,
        double targetDurationSec,
        AspectRatio aspectRatio,
        List<ReferenceAssetBinding> referenceAssets,
        StylePreferences stylePreferences,
        String suppressDetailAreas,
        ImperfectionLevel imperfectionLevel,
        String specificImperfections) {

    public RawUserPromptInput(String requestId, String userDescription, double targetDurationSec, AspectRatio aspectRatio,
                              List<ReferenceAssetBinding> referenceAssets, StylePreferences stylePreferences) {
        this(requestId, userDescription, targetDurationSec, aspectRatio, referenceAssets, stylePreferences, null, null, null);
    }

    public RawUserPromptInput(String requestId, String userDescription, double targetDurationSec, AspectRatio aspectRatio) {
        this(requestId, userDescription, targetDurationSec, aspectRatio, null, null);
    }
}
