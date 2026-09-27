package org.yazi.motion;

import org.yazi.motion.domain.AspectRatio;
import org.yazi.motion.domain.AssetType;
import org.yazi.motion.domain.CameraMovement;
import org.yazi.motion.domain.EngineState;
import org.yazi.motion.domain.ImperfectionLevel;
import org.yazi.motion.domain.MovementSpeed;
import org.yazi.motion.domain.ShotType;
import org.yazi.motion.domain.TemporalSegment;
import org.yazi.motion.domain.PromptPackage;
import org.yazi.motion.domain.RawUserPromptInput;
import org.yazi.motion.domain.ReferenceAssetBinding;
import org.yazi.motion.domain.SpatialPosition;
import org.yazi.motion.domain.StylePreferences;
import org.yazi.motion.domain.VisualStylePreset;
import org.yazi.motion.domain.exception.OutOfScopeDomainException;
import org.yazi.motion.pipeline.PipelineResult;
import org.yazi.motion.pipeline.PromptPipelineOrchestrator;

import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.CompletionException;

/** Port of the Stage-3 "Integration Verification Executable Block". Returns true when every assertion holds. */
public final class SystemVerification {
    private SystemVerification() {}

    public static RawUserPromptInput validPayload() {
        return new RawUserPromptInput("req_test_001",
                "10 second epic slow motion shot of a mechanical dragon flying through volumetric cloudscape",
                10.0, AspectRatio.RATIO_16_9,
                List.of(new ReferenceAssetBinding("asset_dragon_model", "https://storage.internal/models/dragon.obj",
                        AssetType.CHARACTER, SpatialPosition.SUBJECT, 0.0, 10.0, 0.95)),
                new StylePreferences(VisualStylePreset.HYPERREALISTIC_8K, 50.0, null, null, null, null));
    }

    public static RawUserPromptInput invalidPayload() {
        return new RawUserPromptInput("req_test_002", "Write a python script to sort a list of numbers", 5.0, AspectRatio.RATIO_16_9);
    }

    public static boolean runSystemVerification(PrintStream out) {
        PromptPipelineOrchestrator orchestrator = new PromptPipelineOrchestrator();
        boolean ok = true;

        // Test Case A: valid video generation request
        PromptPackage output = orchestrator.execute(validPayload()).join();
        ok &= check(out, "A: positive prompt names HYPERREALISTIC_8K",
                output.compiledPrompts().positivePromptSummary().contains("HYPERREALISTIC_8K"));
        ok &= check(out, "A: timeline has segments", !output.temporalSegments().isEmpty());
        ok &= check(out, "A: status is COMPILED", orchestrator.getPipelineStatus() == EngineState.COMPILED);

        // Test Case B: reject non-video intent
        try {
            orchestrator.execute(invalidPayload()).join();
            ok &= check(out, "B: out-of-scope input rejected", false);
        } catch (CompletionException e) {
            ok &= check(out, "B: OutOfScopeDomainException raised", e.getCause() instanceof OutOfScopeDomainException);
            ok &= check(out, "B: status is FAILED", orchestrator.getPipelineStatus() == EngineState.FAILED);
        }
        ok &= runScenePortScenarios(out);
        out.println(ok ? "System verification PASSED" : "System verification FAILED");
        return ok;
    }

    private static RawUserPromptInput scene(String id, String description, double duration, String suppress,
                                            ImperfectionLevel imperfection, String specific) {
        return new RawUserPromptInput(id, description, duration, AspectRatio.RATIO_16_9, null, null, suppress, imperfection, specific);
    }

    /** Scenarios for the ported web-page behaviour (domain filter, dynamism, angles, lighting, focus budget, imperfection). */
    public static boolean runScenePortScenarios(PrintStream out) {
        PromptPipelineOrchestrator orchestrator = new PromptPipelineOrchestrator();
        boolean ok = true;

        PipelineResult r = orchestrator.run(scene("scn_tree", "a lone tree in a misty field at dawn", 8, null, null, null));
        ok &= check(out, "C: 'a lone tree in a misty field at dawn' accepted", r instanceof PipelineResult.Success);

        r = orchestrator.run(scene("scn_perfume", "a perfume bottle rotating on a pedestal", 8, null, null, null));
        ok &= check(out, "D: 'a perfume bottle rotating on a pedestal' accepted", r instanceof PipelineResult.Success);

        r = orchestrator.run(scene("scn_python", "write me a python script", 8, null, null, null));
        ok &= check(out, "E: 'write me a python script' rejected",
                r instanceof PipelineResult.Failure f && f.exception() instanceof OutOfScopeDomainException);

        r = orchestrator.run(scene("scn_serene", "serene sunrise hike, slow and peaceful", 12, null, null, null));
        if (r instanceof PipelineResult.Success s) {
            List<TemporalSegment> t = s.promptPackage().temporalSegments();
            ok &= check(out, "F: serene hike -> soft lighting ('" + s.promptPackage().styleMatrix().lightingProfile() + "')",
                    s.promptPackage().styleMatrix().lightingProfile().startsWith("soft "));
            ok &= check(out, "F: serene hike -> slow speed and slow pacing", t.stream().allMatch(x ->
                    x.kinematics().movementSpeed() == MovementSpeed.SLOW && x.pacingNote().startsWith("Slow")));
            ok &= check(out, "F: serene hike -> static/crane movements", t.stream().allMatch(x ->
                    x.kinematics().movement() == CameraMovement.STATIC || x.kinematics().movement() == CameraMovement.CRANE_UP));
        } else {
            ok &= check(out, "F: serene hike compiles", false);
        }

        r = orchestrator.run(scene("scn_chase", "intense car chase, night city, explosive", 12, null, null, null));
        if (r instanceof PipelineResult.Success s) {
            List<TemporalSegment> t = s.promptPackage().temporalSegments();
            ok &= check(out, "G: car chase -> hard lighting ('" + s.promptPackage().styleMatrix().lightingProfile() + "')",
                    s.promptPackage().styleMatrix().lightingProfile().startsWith("hard "));
            ok &= check(out, "G: car chase -> fast speed and fast pacing", t.stream().allMatch(x ->
                    x.kinematics().movementSpeed() == MovementSpeed.FAST && x.pacingNote().startsWith("Fast")));
            ok &= check(out, "G: car chase -> tracking/handheld movements", t.stream().allMatch(x ->
                    x.kinematics().movement() == CameraMovement.TRACKING || x.kinematics().movement() == CameraMovement.HANDHELD_SHAKE));
        } else {
            ok &= check(out, "G: car chase compiles", false);
        }

        r = orchestrator.run(scene("scn_hands", "extreme close up of hands trembling", 5, null, null, null));
        ok &= check(out, "H: hands trembling -> EXTREME_CLOSE_UP + STATIC",
                r instanceof PipelineResult.Success s && s.promptPackage().temporalSegments().stream().allMatch(x ->
                        x.kinematics().shotType() == ShotType.EXTREME_CLOSE_UP && x.kinematics().movement() == CameraMovement.STATIC));

        r = orchestrator.run(scene("scn_light", "a perfume bottle rotating on a pedestal", 8, null, ImperfectionLevel.LIGHT, null));
        ok &= check(out, "I: imperfection LIGHT -> 'film grain' in positive prompt",
                r instanceof PipelineResult.Success s && s.promptPackage().compiledPrompts().positivePromptSummary().contains("film grain"));

        r = orchestrator.run(scene("scn_suppress", "a dancer spins across a stage", 8, "sky, ground", null, null));
        ok &= check(out, "J: suppress 'sky, ground' -> 'highly detailed sky/ground' in negative prompt",
                r instanceof PipelineResult.Success s
                        && s.promptPackage().compiledPrompts().negativePromptSummary().contains("highly detailed sky")
                        && s.promptPackage().compiledPrompts().negativePromptSummary().contains("highly detailed ground"));
        return ok;
    }

    private static boolean check(PrintStream out, String name, boolean condition) {
        out.println((condition ? "  [PASS] " : "  [FAIL] ") + name);
        return condition;
    }

    public static void main(String[] args) {
        System.exit(runSystemVerification(System.out) ? 0 : 1);
    }
}
