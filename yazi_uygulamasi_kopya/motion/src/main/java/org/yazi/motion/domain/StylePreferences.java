package org.yazi.motion.domain;

import java.util.List;

/**
 * Java form of {@code Partial<VisualStyleMatrix>}: every field is optional (null = not specified).
 * Non-null values override inferred and default values in the style engine.
 */
public record StylePreferences(
        VisualStylePreset preset,
        Double lensMm,
        String lightingProfile,
        List<String> colorPalette,
        Double atmosphericDensity,
        Double fps) {

    public static StylePreferences none() {
        return new StylePreferences(null, null, null, null, null, null);
    }
}
