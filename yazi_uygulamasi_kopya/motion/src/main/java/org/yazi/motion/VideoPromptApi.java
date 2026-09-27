package org.yazi.motion;

import org.yazi.motion.domain.PromptPackage;
import org.yazi.motion.json.PromptPackageJson;
import org.yazi.motion.pipeline.PipelineResult;
import org.yazi.motion.pipeline.PromptPipelineOrchestrator;

import java.time.Clock;

/**
 * Public API surface (build step 10, the Java counterpart of {@code index.ts}).
 * <ul>
 *   <li>{@code org.yazi.motion.domain} – types and {@code domain.exception} taxonomy</li>
 *   <li>{@code org.yazi.motion.state} – {@code PipelineStateGuard}</li>
 *   <li>{@code org.yazi.motion.engines} – the five engines, their interfaces and {@code ParsedIntentResult}</li>
 *   <li>{@code org.yazi.motion.pipeline} – {@code PromptPipelineOrchestrator}, {@code PipelineSession}, {@code PipelineResult}</li>
 * </ul>
 */
public final class VideoPromptApi {
    private VideoPromptApi() {}

    public static PromptPipelineOrchestrator orchestrator() {
        return new PromptPipelineOrchestrator();
    }

    public static PromptPipelineOrchestrator orchestrator(Clock clock) {
        return new PromptPipelineOrchestrator(clock);
    }

    public static String toJson(PromptPackage pkg) {
        return PromptPackageJson.toJson(pkg, true);
    }

    public static String toJson(PipelineResult result) {
        return PromptPackageJson.toJson(result, true);
    }
}
