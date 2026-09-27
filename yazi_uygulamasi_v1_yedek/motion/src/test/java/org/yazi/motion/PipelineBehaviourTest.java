package org.yazi.motion;

import org.yazi.motion.domain.AspectRatio;
import org.yazi.motion.domain.CameraMovement;
import org.yazi.motion.domain.EngineState;
import org.yazi.motion.domain.MovementSpeed;
import org.yazi.motion.domain.PromptPackage;
import org.yazi.motion.domain.RawUserPromptInput;
import org.yazi.motion.domain.ReferenceAssetBinding;
import org.yazi.motion.domain.ShotType;
import org.yazi.motion.domain.StylePreferences;
import org.yazi.motion.domain.TemporalSegment;
import org.yazi.motion.domain.VisualStylePreset;
import org.yazi.motion.domain.exception.IllegalStateTransitionException;
import org.yazi.motion.engines.VendorSyntax;
import org.yazi.motion.pipeline.PipelineResult;
import org.yazi.motion.pipeline.PipelineSession;
import org.yazi.motion.pipeline.PromptPipelineOrchestrator;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.yazi.motion.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Behaviour carried over from Stages 1-2, checked through the Stage-3 orchestrator. */
class PipelineBehaviourTest {

    private final PromptPipelineOrchestrator orchestrator = new PromptPipelineOrchestrator(FIXED);

    private PipelineResult.Success success(RawUserPromptInput in) {
        PipelineResult r = orchestrator.run(in);
        if (r instanceof PipelineResult.Failure f) fail("expected COMPILED but got " + f.errorCode() + ": " + f.message());
        return (PipelineResult.Success) r;
    }

    private PipelineResult.Failure failure(RawUserPromptInput in) {
        PipelineResult r = orchestrator.run(in);
        assertInstanceOf(PipelineResult.Failure.class, r, "expected FAILED");
        return (PipelineResult.Failure) r;
    }

    @Test
    void identicalInputProducesIdenticalPackage() {
        RawUserPromptInput a = new RawUserPromptInput("same", SCENE, 12, AspectRatio.RATIO_9_16);
        assertEquals(success(a).promptPackage(), success(a).promptPackage());
        assertEquals("2026-09-16T12:00:00Z", success(a).promptPackage().metadata().compiledAt());
    }

    @Test
    void segmentsAreTwoToFiveSeconds() {
        for (double d : new double[]{2, 4.5, 7, 11, 23, 60}) {
            for (TemporalSegment s : success(input(SCENE, d)).promptPackage().temporalSegments()) {
                assertTrue(s.durationSec() >= 2.0 - EPS && s.durationSec() <= 5.0 + EPS, "d=" + d + " -> " + s.durationSec());
            }
        }
    }

    @Test
    void longerBeatsReceiveMoreScreenTime() {
        List<TemporalSegment> t = success(input("A car drives past. Then the detective walks through the crowded "
                + "rainy market looking at every stall and every face", 7)).promptPackage().temporalSegments();
        assertEquals(2, t.size());
        assertTrue(t.get(1).durationSec() > t.get(0).durationSec());
    }

    @Test
    void conflictingPacingSplitsIntoSequentialSegments() {
        List<TemporalSegment> t = success(input("Close-up of a flower blooming in timelapse and in slow motion", 8))
                .promptPackage().temporalSegments();
        assertEquals(2, t.size());
        assertTrue(t.get(0).pacingNote().startsWith("Timelapse"));
        assertTrue(t.get(1).pacingNote().startsWith("Slow motion"));
    }

    @Test
    void explicitCuesMapToStage3Vocabulary() {
        List<TemporalSegment> t = success(input("Aerial drone shot over a foggy valley. Then the camera orbits a hiker. "
                + "Extreme close-up of her eyes in slow motion, low angle", 12)).promptPackage().temporalSegments();
        assertEquals(ShotType.AERIAL, t.get(0).kinematics().shotType());
        assertEquals(CameraMovement.TRACKING, t.get(1).kinematics().movement());
        assertEquals(ShotType.EXTREME_CLOSE_UP, t.get(2).kinematics().shotType());
        assertEquals(MovementSpeed.SLOW, t.get(2).kinematics().movementSpeed());
        assertTrue(t.get(2).pacingNote().endsWith("low angle"));
    }

    @Test
    void outOfScopeRequestsAreRejected() {
        for (String d : List.of("Write me an article about climate change", "Please write a poem about love",
                "Write a screenplay about two friends", "Implement a function that parses JSON",
                "Bana yapay zeka hakkında makale yaz", "Solve this math problem: 3x + 5 = 20")) {
            PipelineResult.Failure f = failure(input(d, 10));
            assertEquals("ERR_OUT_OF_SCOPE_DOMAIN", f.errorCode(), d);
            assertEquals(EngineState.INGESTED, f.failedAt());
        }
        success(input("Close-up shot of a programmer typing source code at night", 8));
    }

    @Test
    void turkishDescriptionsAreSupported() {
        PromptPackage p = success(input("Gece yağmurlu bir şehirde yürüyen bir kadın, sonra kamera yakınlaşır", 10)).promptPackage();
        assertTrue(p.styleMatrix().lightingProfile().contains("moonlit"));
        assertEquals(0.45, p.styleMatrix().atmosphericDensity(), 0.0);
        assertTrue(p.temporalSegments().getFirst().description().endsWith("Focus: kadın"));
        assertEquals(CameraMovement.ZOOM_IN, p.temporalSegments().getLast().kinematics().movement());
    }

    @Test
    void structuralProblemsAreInvalidPayloads() {
        List<RawUserPromptInput> bad = List.of(
                input("   ", 10),
                new RawUserPromptInput("", SCENE, 10, AspectRatio.RATIO_1_1),
                new RawUserPromptInput(nextId(), SCENE, 10, null),
                input(SCENE, 10, Arrays.asList((ReferenceAssetBinding) null)),
                input(SCENE, 10, List.of(asset("dup", 0, 3, 1), asset("dup", 3, 6, 1))),
                input(SCENE, 10, List.of(new ReferenceAssetBinding("x", "no-scheme.png", null, null, 0, 2, 1))));
        for (RawUserPromptInput in : bad) {
            PipelineResult.Failure f = failure(in);
            assertEquals("ERR_INVALID_PAYLOAD", f.errorCode(), f.message());
            assertEquals(EngineState.UNINITIALIZED, f.failedAt());
        }
    }

    @Test
    void assetRepairsAreReportedAndDefaultsApplied() {
        PipelineResult.Success s = success(input(SCENE, 10, List.of(
                new ReferenceAssetBinding("a", "s3://b/a.png", null, null, 4, 12, 1.7))));
        ReferenceAssetBinding b = s.promptPackage().boundAssets().getFirst();
        assertEquals(10.0, b.temporalEndSec(), 0.0);
        assertEquals(1.0, b.weight(), 0.0);
        assertEquals("STYLE", b.assetType().name());
        assertEquals("SUBJECT", b.spatialPosition().name());
        assertEquals(2, s.warnings().size());
    }

    @Test
    void partiallyOverlappingAssetsAppearInTheBreakdown() {
        String text = success(input(SCENE, 12, List.of(asset("mid", 3, 7, 0.6)))).promptPackage()
                .compiledPrompts().temporalBreakdownText();
        assertTrue(text.contains("mid(w:0.6)"), text);
    }

    @Test
    void assetBoundaryFailuresHappenAtTimelinePlanned() {
        for (ReferenceAssetBinding a : List.of(asset("late", 10, 12, 1), asset("neg", -1, 3, 1), asset("empty", 4, 4, 1))) {
            PipelineResult.Failure f = failure(input(SCENE, 10, List.of(a)));
            assertEquals("ERR_ASSET_TEMPORAL_OUT_OF_BOUNDS", f.errorCode(), a.assetId());
            assertEquals(EngineState.TIMELINE_PLANNED, f.failedAt());
        }
    }

    @Test
    void compiledPromptsStayEngineAgnostic() {
        PromptPackage p = success(new RawUserPromptInput(nextId(), SCENE + " --ar 16:9 --v 6 --seed 42 cat::2", 10,
                AspectRatio.RATIO_16_9, List.of(), new StylePreferences(VisualStylePreset.NEO_NOIR, null, null, null, null, null)))
                .promptPackage();
        var c = p.compiledPrompts();
        for (String text : List.of(c.positivePromptSummary(), c.negativePromptSummary(), c.temporalBreakdownText())) {
            assertFalse(VendorSyntax.containsVendorSyntax(text), text);
        }
    }

    @Test
    void explicitStyleOverridesAreApplied() {
        PromptPackage p = success(new RawUserPromptInput(nextId(), "A neon alley at night", 6, AspectRatio.RATIO_21_9, null,
                new StylePreferences(null, 85.0, " Single hard key light ", List.of("#abc", " teal  blue "), 3.0, 30.0))).promptPackage();
        var m = p.styleMatrix();
        assertEquals(VisualStylePreset.MVT_CYBERPUNK, m.preset());
        assertEquals(85.0, m.lensMm(), 0.0);
        assertEquals("Single hard key light", m.lightingProfile());
        assertEquals(List.of("#AABBCC", "Teal Blue"), m.colorPalette());
        assertEquals(1.0, m.atmosphericDensity(), 0.0);
        assertEquals(30.0, m.fps(), 0.0);
        assertTrue(p.temporalSegments().stream().allMatch(s -> s.kinematics().focalLengthMm() == 85.0));
    }

    @Test
    void failedRequestIdCannotBeReused() {
        RawUserPromptInput bad = new RawUserPromptInput("retry-me", SCENE, 90, AspectRatio.RATIO_16_9);
        assertEquals("ERR_INVALID_DURATION", failure(bad).errorCode());
        PipelineResult.Failure again = failure(new RawUserPromptInput("retry-me", SCENE, 10, AspectRatio.RATIO_16_9));
        assertEquals("ERR_ILLEGAL_STATE_TRANSITION", again.errorCode());
        success(new RawUserPromptInput("retry-me-2", SCENE, 10, AspectRatio.RATIO_16_9));
    }

    @Test
    void outOfOrderSessionStepsAreIllegal() {
        PipelineSession session = orchestrator.newSession(input(SCENE, 10));
        session.ingest();
        assertThrows(IllegalStateTransitionException.class, session::compile);
        assertEquals(EngineState.FAILED, session.state());
        assertEquals(EngineState.INTENT_PARSED, session.failedAt());
        assertThrows(IllegalStateTransitionException.class, session::decompose);

        PipelineSession noStyle = orchestrator.newSession(input(SCENE, 10));
        noStyle.ingest();
        noStyle.decompose();
        assertThrows(IllegalStateTransitionException.class, noStyle::bindAssets);
    }

    @Test
    void orchestratorIsSafeToShareAcrossThreads() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.CompletableFuture<PipelineResult>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 40; i++) {
                futures.add(orchestrator.runAsync(i % 2 == 0 ? input(SCENE, 5 + i) : input("Write me an essay", 10), pool));
            }
            for (int i = 0; i < futures.size(); i++) {
                PipelineResult r = futures.get(i).get();
                assertEquals(i % 2 == 0 ? EngineState.COMPILED : EngineState.FAILED, r.finalState());
            }
        } finally {
            pool.shutdown();
        }
    }
}
