package org.yazi.motion.engines;

import org.yazi.motion.domain.ImperfectionLevel;

import java.util.List;

/**
 * Scene-dependent inputs for the compiled prompt blocks.
 *
 * @param sceneDescription      sanitized description, used to detect the main focus for the negative prompt
 * @param suppressDetailAreas   parsed user areas; when non-empty they replace the automatic focus decision
 * @param imperfectionLevel     OFF keeps the positive prompt unchanged
 * @param specificImperfections user text appended verbatim (vendor tokens stripped) when the level is not OFF
 */
public record PromptOptions(String sceneDescription, List<String> suppressDetailAreas, ImperfectionLevel imperfectionLevel,
                            String specificImperfections) {

    public PromptOptions {
        suppressDetailAreas = suppressDetailAreas == null ? List.of() : List.copyOf(suppressDetailAreas);
        imperfectionLevel = imperfectionLevel == null ? ImperfectionLevel.OFF : imperfectionLevel;
        specificImperfections = specificImperfections == null ? "" : specificImperfections;
    }

    public static PromptOptions none() {
        return new PromptOptions(null, List.of(), ImperfectionLevel.OFF, "");
    }
}
