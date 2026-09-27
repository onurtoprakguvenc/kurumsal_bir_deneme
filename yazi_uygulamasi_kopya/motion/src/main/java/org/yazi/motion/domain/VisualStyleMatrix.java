package org.yazi.motion.domain;

import java.util.List;

import static org.yazi.motion.domain.DomainValidation.*;

public record VisualStyleMatrix(
        VisualStylePreset preset,
        double lensMm,
        String lightingProfile,
        List<String> colorPalette,
        double atmosphericDensity,
        double fps) {

    public VisualStyleMatrix {
        require(preset, "preset");
        requirePositive(lensMm, "lens_mm");
        requireText(lightingProfile, "lighting_profile");
        colorPalette = immutable(colorPalette);
        if (colorPalette.isEmpty()) throw new IllegalArgumentException("color_palette must not be empty");
        requireRange(atmosphericDensity, 0.0, 1.0, "atmospheric_density");
        requirePositive(fps, "fps");
    }
}
