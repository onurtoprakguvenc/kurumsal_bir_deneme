package org.yazi.motion;

import org.yazi.motion.domain.CameraAngle;
import org.yazi.motion.domain.CameraKinematics;
import org.yazi.motion.domain.CameraMovement;
import org.yazi.motion.domain.EngineState;
import org.yazi.motion.domain.MovementSpeed;
import org.yazi.motion.domain.ShotType;
import org.yazi.motion.domain.TemporalSegment;
import org.yazi.motion.domain.VisualStyleMatrix;
import org.yazi.motion.domain.exception.*;
import org.yazi.motion.engines.CinematicStyleMatrixEngine;
import org.yazi.motion.engines.PromptPackageCompiler;
import org.yazi.motion.engines.TemporalContinuity;
import org.yazi.motion.state.PipelineStateGuard;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.yazi.motion.domain.AspectRatio.RATIO_16_9;
import static org.junit.jupiter.api.Assertions.*;

class ComponentTest {

    private static TemporalSegment seg(int i, double start, double end) {
        return new TemporalSegment(i, start, end, end - start, "Action " + i,
                new CameraKinematics(ShotType.WIDE_SHOT, CameraAngle.EYE_LEVEL, CameraMovement.STATIC, MovementSpeed.SLOW, 35), "Realtime pacing");
    }

    @Test
    void exceptionTaxonomy() {
        List<PromptEngineException> all = List.of(new InvalidPayloadException("m"), new OutOfScopeDomainException("m"),
                new InvalidDurationException("m"), new TemporalDecompositionException("m"), new TemporalGapException("m"),
                new AssetBoundaryException("m"), new IllegalStateTransitionException("m"),
                new CompilationIncompleteException("m", Map.of("k", 1)));
        assertEquals(List.of("ERR_INVALID_PAYLOAD", "ERR_OUT_OF_SCOPE_DOMAIN", "ERR_INVALID_DURATION",
                        "ERR_TEMPORAL_DECOMPOSITION_FAILED", "ERR_TEMPORAL_GAP_DETECTED", "ERR_ASSET_TEMPORAL_OUT_OF_BOUNDS",
                        "ERR_ILLEGAL_STATE_TRANSITION", "ERR_COMPILATION_FAILED"),
                all.stream().map(PromptEngineException::errorCode).toList());
        assertEquals(8, new HashSet<>(all.stream().map(PromptEngineException::errorCode).toList()).size());
        for (PromptEngineException e : all) {
            assertTrue(e.timestamp() > 0);
            assertEquals(e.getClass().getSimpleName(), e.name());
        }
        assertThrows(UnsupportedOperationException.class, () -> all.getLast().details().put("x", 2));
    }

    @Test
    void stateGuardFollowsTransitionTable() {
        PipelineStateGuard g = new PipelineStateGuard();
        IllegalStateTransitionException ex = assertThrows(IllegalStateTransitionException.class, () -> g.transitionTo(EngineState.COMPILED));
        assertEquals("UNINITIALIZED", ex.details().get("current_state"));
        g.transitionTo(EngineState.INGESTED);
        g.transitionTo(EngineState.FAILED);
        assertThrows(IllegalStateTransitionException.class, () -> g.transitionTo(EngineState.INTENT_PARSED));
        g.transitionTo(EngineState.UNINITIALIZED);
        for (EngineState s : List.of(EngineState.INGESTED, EngineState.INTENT_PARSED, EngineState.TIMELINE_PLANNED,
                EngineState.ASSETS_BOUND, EngineState.COMPILED)) {
            g.transitionTo(s);
        }
        assertFalse(g.canTransitionTo(EngineState.FAILED));
        g.reset();
        assertEquals(EngineState.UNINITIALIZED, g.getCurrentState());
    }

    @Test
    void continuityGuard() {
        assertThrows(TemporalDecompositionException.class, () -> TemporalContinuity.assertTemporalContinuity(List.of(), 5));
        assertThrows(TemporalGapException.class, () -> TemporalContinuity.assertTemporalContinuity(List.of(seg(0, 0.5, 5)), 5));
        TemporalGapException gap = assertThrows(TemporalGapException.class,
                () -> TemporalContinuity.assertTemporalContinuity(List.of(seg(0, 0, 3), seg(1, 4, 5)), 5));
        assertEquals(3.0, gap.details().get("segment_end"));
        TemporalContinuity.assertTemporalContinuity(List.of(seg(0, 0.0004, 3), seg(1, 3.0009, 14.9995)), 15);
    }

    @Test
    void compilerGuards() {
        var compiler = new PromptPackageCompiler(TestSupport.FIXED);
        VisualStyleMatrix style = new CinematicStyleMatrixEngine().resolveStyleMatrix(null, "city at night");
        List<TemporalSegment> ok = List.of(seg(0, 0, 5), seg(1, 5, 10));
        assertThrows(CompilationIncompleteException.class, () -> compiler.compile("", 10, RATIO_16_9, style, ok, List.of()));
        assertThrows(CompilationIncompleteException.class, () -> compiler.compile("r", 10, RATIO_16_9, style, List.of(), List.of()));
        assertThrows(CompilationIncompleteException.class, () -> compiler.compile("r", 10, RATIO_16_9, null, ok, List.of()));
        assertThrows(TemporalGapException.class,
                () -> compiler.compile("r", 10, RATIO_16_9, style, List.of(seg(0, 0, 4), seg(1, 5, 10)), List.of()));
        var pkg = compiler.compile("r", 10, RATIO_16_9, style, ok, null);
        assertEquals("2026.1", pkg.schemaVersion());
        assertEquals("1.0.0-production", pkg.metadata().engineVersion());
        assertTrue(pkg.compiledPrompts().temporalBreakdownText().startsWith("[0.00s -> 5.00s] WIDE_SHOT, EYE_LEVEL, STATIC (SLOW) - Action 0"));
    }

    @Test
    void paletteNormalization() {
        assertEquals(List.of("#AABBCC", "Teal Blue", "#1A2B3C"),
                CinematicStyleMatrixEngine.normalizePalette(List.of("#abc", " teal  blue ", "#1a2B3c", "", "#ABC")));
    }

    @Test
    void presetInferenceUsesWordBoundaries() {
        var engine = new CinematicStyleMatrixEngine();
        assertEquals("NEO_NOIR", engine.resolveStyleMatrix(null, "a neo-noir detective in the rain").preset().name());
        assertEquals("CINEMATIC_35MM", engine.resolveStyleMatrix(null, "a dark-haired girl reads by a window").preset().name());
        assertEquals("MVT_CYBERPUNK", engine.resolveStyleMatrix(null, "neon signs flicker over the street").preset().name());
    }
}
