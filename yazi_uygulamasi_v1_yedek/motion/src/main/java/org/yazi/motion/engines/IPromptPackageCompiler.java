package org.yazi.motion.engines;

import org.yazi.motion.domain.AspectRatio;
import org.yazi.motion.domain.PromptPackage;
import org.yazi.motion.domain.ReferenceAssetBinding;
import org.yazi.motion.domain.TemporalSegment;
import org.yazi.motion.domain.VisualStyleMatrix;

import java.util.List;

public interface IPromptPackageCompiler {
    /**
     * Assembles all validated sub-components into the final immutable PromptPackage payload.
     * @throws org.yazi.motion.domain.exception.TemporalGapException re-evaluates temporal completeness before output.
     * @throws org.yazi.motion.domain.exception.CompilationIncompleteException if required prompt attributes are missing.
     */
    default PromptPackage compile(String requestId, double targetDuration, AspectRatio aspectRatio, VisualStyleMatrix style,
                                  List<TemporalSegment> timeline, List<ReferenceAssetBinding> assets) {
        return compile(requestId, targetDuration, aspectRatio, style, timeline, assets, PromptOptions.none());
    }

    /** As above, with scene focus, suppress areas and imperfection settings for the prompt blocks. */
    PromptPackage compile(String requestId, double targetDuration, AspectRatio aspectRatio, VisualStyleMatrix style,
                          List<TemporalSegment> timeline, List<ReferenceAssetBinding> assets, PromptOptions options);
}
