package org.yazi.motion;

import org.yazi.motion.domain.EngineState;
import org.yazi.motion.domain.ReferenceAssetBinding;
import org.yazi.motion.domain.TemporalSegment;
import org.yazi.motion.domain.VisualStyleMatrix;
import org.yazi.motion.domain.VisualStylePreset;
import org.yazi.motion.domain.exception.AssetBoundaryException;
import org.yazi.motion.domain.exception.InvalidDurationException;
import org.yazi.motion.domain.exception.OutOfScopeDomainException;
import org.yazi.motion.engines.CinematicStyleMatrixEngine;
import org.yazi.motion.engines.ParsedIntentResult;
import org.yazi.motion.engines.ReferenceAssetBinder;
import org.yazi.motion.engines.TemporalKinematicDecomposer;
import org.yazi.motion.engines.UserIntentIngestionEngine;
import org.yazi.motion.pipeline.PromptPipelineOrchestrator;
import org.yazi.motion.state.PipelineStateGuard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletionException;

import static org.yazi.motion.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** The Stage-3 "Suite Assertions Matrix", one test per row. */
class VerificationChecklistTest {

    private final UserIntentIngestionEngine ingestion = new UserIntentIngestionEngine();
    private final TemporalKinematicDecomposer decomposer = new TemporalKinematicDecomposer();

    private static Throwable failureOf(java.util.concurrent.CompletableFuture<?> future) {
        CompletionException ex = assertThrows(CompletionException.class, future::join);
        return ex.getCause();
    }

    private ParsedIntentResult intent(String description, double duration) {
        return ingestion.ingestAndValidate(input(description, duration)).join();
    }

    // [Ingestion Engine]

    @Test
    void test_1_1_rejectsNonVideoDomain() {
        Throwable t = failureOf(ingestion.ingestAndValidate(input("Write a Python script", 5)));
        assertInstanceOf(OutOfScopeDomainException.class, t);
        assertEquals("ERR_OUT_OF_SCOPE_DOMAIN", ((OutOfScopeDomainException) t).errorCode());
    }

    @Test
    void test_1_2_rejectsDurationsOutsideBounds() {
        for (double d : new double[]{0.5, 60.5}) {
            Throwable t = failureOf(ingestion.ingestAndValidate(input("A dog runs along a beach", d)));
            assertInstanceOf(InvalidDurationException.class, t);
            assertEquals("ERR_INVALID_DURATION", ((InvalidDurationException) t).errorCode());
        }
    }

    @Test
    void test_1_3_acceptsValidVideoPrompt() {
        ParsedIntentResult r = intent("15 second comedic transition", 15);
        assertTrue(r.isVideoDomain());
        assertEquals("15 second comedic transition", r.sanitizedDescription());
    }

    // [Temporal Kinematic Decomposer]

    @Test
    void test_2_1_durationsSumToTarget() {
        for (double d : new double[]{1, 7.25, 15, 33.3, 60}) {
            List<TemporalSegment> t = decomposer.decomposeTimeline(intent(SCENE, d)).join();
            assertEquals(d, t.stream().mapToDouble(TemporalSegment::durationSec).sum(), EPS * t.size());
        }
    }

    @Test
    void test_2_2_zeroGapInvariant() {
        List<TemporalSegment> t = decomposer.decomposeTimeline(intent(SCENE, 23)).join();
        assertEquals(0.0, t.getFirst().startSecond(), 0.0);
        for (int i = 0; i < t.size() - 1; i++) assertEquals(t.get(i).endSecond(), t.get(i + 1).startSecond(), 0.0);
        assertEquals(23.0, t.getLast().endSecond(), 0.0);
    }

    @Test
    void test_2_3_noSubMillisecondDrift() {
        for (double d : new double[]{3.3333, 7.12345, 14.9999, 59.999}) {
            List<TemporalSegment> t = decomposer.decomposeTimeline(intent(SCENE, d)).join();
            for (int i = 0; i < t.size() - 1; i++) {
                double end = t.get(i).endSecond();
                assertEquals(end, Math.round(end * 1000) / 1000.0, 0.0, "boundary not rounded to ms: " + end);
            }
            assertEquals(d, t.getLast().endSecond(), 0.0, "residual must land on the final segment");
        }
    }

    // [Cinematic Style Engine]

    @Test
    void test_3_1_defaultsWhenPreferencesOmitted() {
        VisualStyleMatrix m = new CinematicStyleMatrixEngine().resolveStyleMatrix(null, null);
        assertEquals(VisualStylePreset.CINEMATIC_35MM, m.preset());
        assertEquals(35.0, m.lensMm(), 0.0);
        assertEquals("High contrast cinematic volumetric key lighting", m.lightingProfile());
        assertEquals(List.of("#1A1A1A", "#D4AF37", "#0A192F"), m.colorPalette());
        assertEquals(0.35, m.atmosphericDensity(), 0.0);
        assertEquals(24.0, m.fps(), 0.0);
    }

    @Test
    void test_3_2_infersAnimePreset() {
        assertEquals(VisualStylePreset.ANIME,
                new CinematicStyleMatrixEngine().resolveStyleMatrix(null, "anime style fight scene").preset());
    }

    // [Reference Asset Binder]

    @Test
    void test_4_1_clampsEndToDuration() {
        ReferenceAssetBinding b = new ReferenceAssetBinder().bindAssets(List.of(asset("a", 2.0, 20.0, 0.5)), 15.0).getFirst();
        assertEquals(2.0, b.temporalStartSec(), 0.0);
        assertEquals(15.0, b.temporalEndSec(), 0.0);
    }

    @Test
    void test_4_2_rejectsStartAtOrAfterDuration() {
        AssetBoundaryException ex = assertThrows(AssetBoundaryException.class,
                () -> new ReferenceAssetBinder().bindAssets(List.of(asset("late", 15.0, 18.0, 1)), 15.0));
        assertEquals("ERR_ASSET_TEMPORAL_OUT_OF_BOUNDS", ex.errorCode());
    }

    @Test
    void test_4_3_clampsWeights() {
        List<ReferenceAssetBinding> b = new ReferenceAssetBinder().bindAssets(
                List.of(asset("lo", 0, 5, -0.4), asset("hi", 0, 5, 1.8), asset("none", 0, 5, Double.NaN)), 10);
        assertEquals(0.0, b.get(0).weight(), 0.0);
        assertEquals(1.0, b.get(1).weight(), 0.0);
        assertEquals(1.0, b.get(2).weight(), 0.0);
    }

    // [Pipeline Orchestrator]

    @Test
    void test_5_1_endToEndLifecycle() {
        PromptPipelineOrchestrator orchestrator = new PromptPipelineOrchestrator(FIXED);
        assertEquals(EngineState.UNINITIALIZED, orchestrator.getPipelineStatus());
        var result = orchestrator.run(SystemVerification.validPayload());
        assertEquals(List.of(EngineState.INGESTED, EngineState.INTENT_PARSED, EngineState.TIMELINE_PLANNED,
                        EngineState.ASSETS_BOUND, EngineState.COMPILED),
                result.history().stream().map(PipelineStateGuard.Transition::to).toList());
        assertEquals(EngineState.COMPILED, orchestrator.getPipelineStatus());
    }

    @Test
    void test_5_2_failsOnDomainException() {
        PromptPipelineOrchestrator orchestrator = new PromptPipelineOrchestrator(FIXED);
        Throwable t = failureOf(orchestrator.execute(SystemVerification.invalidPayload()));
        assertInstanceOf(OutOfScopeDomainException.class, t);
        assertEquals(EngineState.FAILED, orchestrator.getPipelineStatus());
    }

    // Integration Verification Executable Block

    @Test
    void integrationVerificationBlockPasses() {
        assertTrue(SystemVerification.runSystemVerification(new java.io.PrintStream(java.io.OutputStream.nullOutputStream())));
    }
}
