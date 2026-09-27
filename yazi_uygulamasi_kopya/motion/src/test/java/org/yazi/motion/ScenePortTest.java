package org.yazi.motion;

import org.yazi.motion.domain.AspectRatio;
import org.yazi.motion.domain.CameraAngle;
import org.yazi.motion.domain.CameraMovement;
import org.yazi.motion.domain.ImperfectionLevel;
import org.yazi.motion.domain.MovementSpeed;
import org.yazi.motion.domain.PromptPackage;
import org.yazi.motion.domain.RawUserPromptInput;
import org.yazi.motion.domain.ShotType;
import org.yazi.motion.domain.TemporalSegment;
import org.yazi.motion.engines.SceneAnalysis;
import org.yazi.motion.pipeline.PipelineResult;
import org.yazi.motion.pipeline.PromptPipelineOrchestrator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.yazi.motion.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** The seven web-page changes, ported to the Java engines. */
class ScenePortTest {

    private final PromptPipelineOrchestrator orchestrator = new PromptPipelineOrchestrator(FIXED);

    private RawUserPromptInput scene(String description, double duration, String suppress, ImperfectionLevel level, String specific) {
        return new RawUserPromptInput(nextId(), description, duration, AspectRatio.RATIO_16_9, null, null, suppress, level, specific);
    }

    private PipelineResult.Success ok(RawUserPromptInput in) {
        PipelineResult r = orchestrator.run(in);
        if (r instanceof PipelineResult.Failure f) fail("expected COMPILED but got " + f.errorCode() + ": " + f.message());
        return (PipelineResult.Success) r;
    }

    private PromptPackage pkg(String description, double duration) {
        return ok(scene(description, duration, null, null, null)).promptPackage();
    }

    // ---------- domain filter ----------

    @Test
    void pureSceneDescriptionsAreAccepted() {
        for (String d : List.of("a lone tree in a misty field at dawn", "a perfume bottle rotating on a pedestal",
                "an old bicycle leaning against a brick wall", "steam rising from a cup of coffee", "kırmızı bir şemsiye")) {
            assertInstanceOf(PipelineResult.Success.class, orchestrator.run(scene(d, 8, null, null, null)), d);
        }
    }

    @Test
    void clearlyNonVideoRequestsAreStillRejected() {
        for (String d : List.of("write me a python script", "Write me an article about climate change", "draft an email to my landlord",
                "give me an sql query for monthly sales", "Solve this math problem: 3x + 5 = 20", "Bana yapay zeka hakkında makale yaz")) {
            PipelineResult r = orchestrator.run(scene(d, 8, null, null, null));
            assertInstanceOf(PipelineResult.Failure.class, r, d);
            assertEquals("ERR_OUT_OF_SCOPE_DOMAIN", ((PipelineResult.Failure) r).errorCode(), d);
        }
    }

    // ---------- dynamism ----------

    @Test
    void calmScenesGetLongStaticAndCraneTakes() {
        PromptPackage p = pkg("serene sunrise hike, slow and peaceful", 12);
        for (TemporalSegment s : p.temporalSegments()) {
            assertTrue(s.durationSec() >= 4.0 - EPS);
            assertEquals(MovementSpeed.SLOW, s.kinematics().movementSpeed());
            assertTrue(s.pacingNote().startsWith("Slow-paced"));
            assertTrue(List.of(CameraMovement.STATIC, CameraMovement.CRANE_UP).contains(s.kinematics().movement()));
        }
    }

    @Test
    void intenseScenesGetFastTrackingAndHandheldCuts() {
        List<TemporalSegment> t = pkg("intense car chase, night city, explosive", 12).temporalSegments();
        for (TemporalSegment s : t) {
            assertTrue(s.durationSec() <= 3.0 + EPS);
            assertEquals(MovementSpeed.FAST, s.kinematics().movementSpeed());
            assertTrue(s.pacingNote().startsWith("Fast-paced"));
        }
        assertTrue(t.stream().anyMatch(s -> s.kinematics().movement() == CameraMovement.HANDHELD_SHAKE));
        assertTrue(t.stream().anyMatch(s -> s.kinematics().movement() == CameraMovement.TRACKING));
    }

    @Test
    void slowMotionDoesNotMakeAnActionSceneCalm() {
        List<TemporalSegment> t = pkg("Epic battle, a knight charges in slow motion", 6).temporalSegments();
        assertEquals(List.of(CameraMovement.TRACKING, CameraMovement.HANDHELD_SHAKE), t.stream().map(s -> s.kinematics().movement()).toList());
    }

    @Test
    void handheldIsItsOwnMovement() {
        assertTrue(pkg("A man films with a handheld camera in a market", 6).temporalSegments().stream()
                .anyMatch(s -> s.kinematics().movement() == CameraMovement.HANDHELD_SHAKE));
    }

    // ---------- angles and framing ----------

    @Test
    void extremeCloseUpIsStatic() {
        TemporalSegment s = pkg("extreme close up of hands trembling", 5).temporalSegments().getFirst();
        assertEquals(ShotType.EXTREME_CLOSE_UP, s.kinematics().shotType());
        assertEquals(CameraMovement.STATIC, s.kinematics().movement());
    }

    @Test
    void explicitAnglesAreAssignedToTheirSegment() {
        PromptPackage p = pkg("Low angle shot of a knight. Then a bird's-eye view of the castle", 8);
        assertEquals(CameraAngle.LOW_ANGLE, p.temporalSegments().get(0).kinematics().cameraAngle());
        assertEquals(CameraAngle.BIRD_EYE, p.temporalSegments().get(1).kinematics().cameraAngle());
        assertTrue(p.compiledPrompts().temporalBreakdownText().contains("WIDE_SHOT, LOW_ANGLE, PAN_RIGHT"));
    }

    @Test
    void mergedBeatsKeepLaterCameraCues() {
        List<TemporalSegment> t = pkg("A lone hiker walks. The camera pans left. Close-up of her face. Low angle of the peak. "
                + "Wide shot of the valley. She sits.", 4).temporalSegments();
        assertTrue(t.stream().anyMatch(s -> s.kinematics().movement() == CameraMovement.PAN_LEFT));
        assertTrue(t.stream().anyMatch(s -> s.kinematics().cameraAngle() == CameraAngle.LOW_ANGLE));
    }

    // ---------- lighting ----------

    @Test
    void lightingIsDecidedFromToneAndTime() {
        assertEquals("soft golden hour diffusion, low contrast, warm fill light", pkg("A beach at sunset, peaceful", 8).styleMatrix().lightingProfile());
        assertEquals("hard single-source key light, deep shadows, cold blue rim", pkg("Tense, dark alley shot", 8).styleMatrix().lightingProfile());
        assertEquals("soft sunrise diffusion, low contrast, warm fill light", pkg("serene sunrise hike, slow and peaceful", 12).styleMatrix().lightingProfile());
        assertEquals("High contrast cinematic volumetric key lighting", pkg("extreme close up of hands trembling", 5).styleMatrix().lightingProfile());
    }

    // ---------- focus budget and suppression ----------

    @Test
    void negativePromptFollowsTheSceneFocus() {
        String neg = pkg("A dancer spins and leaps across an empty stage", 8).compiledPrompts().negativePromptSummary();
        assertTrue(neg.startsWith("highly detailed background, intricate pavement texture, complex environment detail, elaborate sky texture"), neg);
        assertEquals(SceneAnalysis.Focus.FACIAL_EXPRESSION, SceneAnalysis.detectFocus("Close-up of an old man's face as tears form in his eyes").orElseThrow());
        assertEquals(SceneAnalysis.Focus.OBJECT, SceneAnalysis.detectFocus("a perfume bottle rotating on a pedestal").orElseThrow());
        assertEquals(SceneAnalysis.Focus.ENVIRONMENT, SceneAnalysis.detectFocus("Aerial view over a misty mountain valley and the horizon").orElseThrow());
        assertTrue(SceneAnalysis.detectFocus("A red light blinks").isEmpty());
    }

    @Test
    void suppressAreasReplaceTheAutomaticDecision() {
        PipelineResult.Success s = ok(scene("A dancer spins and leaps across an empty stage", 8, "sky, ground", null, null));
        String neg = s.promptPackage().compiledPrompts().negativePromptSummary();
        assertTrue(neg.contains("highly detailed sky, intricate sky texture, highly detailed ground, intricate ground texture, elaborate sky, elaborate ground"), neg);
        assertFalse(neg.contains("intricate pavement texture"));
        assertEquals("user", s.focusBudget().source());
        assertEquals(List.of("background", "sky", "ground"), SceneAnalysis.parseSuppressAreas("background, sky, or ground texture"));
    }

    // ---------- imperfection ----------

    @Test
    void imperfectionLevels() {
        String light = ok(scene("a candle flame", 8, null, ImperfectionLevel.LIGHT, null)).promptPackage().compiledPrompts().positivePromptSummary();
        assertTrue(light.contains("Organic imperfection: light film grain, minimal lens distortion, subtle chromatic aberration. Master"));

        PromptPackage pronounced = ok(scene("a candle flame", 8, null, ImperfectionLevel.PRONOUNCED, null)).promptPackage();
        assertTrue(pronounced.compiledPrompts().positivePromptSummary().contains("handheld camera shake, visible film scratches, organic lighting flicker"));
        assertFalse(pronounced.compiledPrompts().negativePromptSummary().contains("flickering visual artifacts"));

        String off = ok(scene("a candle flame", 8, null, ImperfectionLevel.OFF, "wet lens droplets")).promptPackage().compiledPrompts().positivePromptSummary();
        assertFalse(off.contains("Organic imperfection"));
        assertFalse(off.contains("wet lens droplets"));
    }

    @Test
    void specificImperfectionsAreUsedVerbatimWithVendorTokensStripped() {
        String pos = ok(scene("a perfume bottle rotating on a pedestal", 8, null, ImperfectionLevel.LIGHT, "wet lens droplets"))
                .promptPackage().compiledPrompts().positivePromptSummary();
        assertTrue(pos.contains("film grain") && pos.contains("subtle chromatic aberration, wet lens droplets."), pos);

        String vendor = ok(scene("a candle flame", 8, null, ImperfectionLevel.LIGHT, "  heat haze --ar 16:9 ")).promptPackage()
                .compiledPrompts().positivePromptSummary();
        assertTrue(vendor.contains("subtle chromatic aberration, heat haze."), vendor);
    }

    @Test
    void systemVerificationScenariosPass() {
        assertTrue(SystemVerification.runScenePortScenarios(new java.io.PrintStream(java.io.OutputStream.nullOutputStream())));
    }
}
