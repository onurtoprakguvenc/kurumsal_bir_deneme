package org.yazi.motion.pipeline;

import org.yazi.motion.domain.EngineState;
import org.yazi.motion.domain.PromptPackage;
import org.yazi.motion.domain.RawUserPromptInput;
import org.yazi.motion.domain.ReferenceAssetBinding;
import org.yazi.motion.domain.TemporalSegment;
import org.yazi.motion.domain.VisualStyleMatrix;
import org.yazi.motion.domain.exception.IllegalStateTransitionException;
import org.yazi.motion.domain.exception.InvalidDurationException;
import org.yazi.motion.domain.exception.OutOfScopeDomainException;
import org.yazi.motion.domain.exception.PromptEngineException;
import org.yazi.motion.engines.ICinematicStyleMatrixEngine;
import org.yazi.motion.engines.IPromptPackageCompiler;
import org.yazi.motion.engines.IReferenceAssetBinder;
import org.yazi.motion.engines.ITemporalKinematicDecomposer;
import org.yazi.motion.engines.IUserIntentIngestionEngine;
import org.yazi.motion.engines.ParsedIntentResult;
import org.yazi.motion.engines.SceneAnalysis;
import org.yazi.motion.state.PipelineStateGuard;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import static org.yazi.motion.domain.EngineState.*;

/**
 * One transactional pipeline run. Each step checks the guard BEFORE its engine is invoked, so an
 * out-of-order call (compile() right after ingest()) raises ERR_ILLEGAL_STATE_TRANSITION.
 * Any PromptEngineException moves a non-terminal session to FAILED.
 */
public final class PipelineSession {

    private final RawUserPromptInput input;
    private final IUserIntentIngestionEngine ingestion;
    private final ITemporalKinematicDecomposer decomposer;
    private final ICinematicStyleMatrixEngine styleEngine;
    private final IReferenceAssetBinder binder;
    private final IPromptPackageCompiler compiler;
    private final List<String> warnings;
    private final PipelineStateGuard guard = new PipelineStateGuard();

    private ParsedIntentResult intent;
    private List<TemporalSegment> timeline;
    private VisualStyleMatrix style;
    private List<ReferenceAssetBinding> boundAssets;
    private EngineState failedAt;
    private SceneAnalysis.FocusBudget focusBudget;
    private PromptEngineException error;

    PipelineSession(RawUserPromptInput input, IUserIntentIngestionEngine ingestion, ITemporalKinematicDecomposer decomposer,
                    ICinematicStyleMatrixEngine styleEngine, IReferenceAssetBinder binder, IPromptPackageCompiler compiler,
                    List<String> warnings) {
        this.input = input;
        this.ingestion = ingestion;
        this.decomposer = decomposer;
        this.styleEngine = styleEngine;
        this.binder = binder;
        this.compiler = compiler;
        this.warnings = warnings;
    }

    /** Step 1: UNINITIALIZED -> INGESTED -> INTENT_PARSED (schema, then scope and duration). */
    public ParsedIntentResult ingest() {
        return step(() -> {
            guard.assertCanTransition(INGESTED);
            ParsedIntentResult result;
            try {
                result = await(ingestion.ingestAndValidate(input));
            } catch (OutOfScopeDomainException | InvalidDurationException ex) {
                guard.transitionTo(INGESTED); // the schema check passed before this failure
                throw ex;
            }
            guard.transitionTo(INGESTED);
            guard.transitionTo(INTENT_PARSED);
            intent = result;
            return result;
        });
    }

    /** Step 2: INTENT_PARSED -> TIMELINE_PLANNED. */
    public List<TemporalSegment> decompose() {
        return step(() -> {
            guard.assertCanTransition(TIMELINE_PLANNED);
            timeline = await(decomposer.decomposeTimeline(intent));
            guard.transitionTo(TIMELINE_PLANNED);
            return timeline;
        });
    }

    /** Step 3: style synthesis while TIMELINE_PLANNED (no state change). */
    public VisualStyleMatrix resolveStyle() {
        return step(() -> {
            requireState(TIMELINE_PLANNED, "resolve the style matrix");
            style = styleEngine.resolveStyleMatrix(intent.explicitStylePreferences(), intent.sanitizedDescription());
            return style;
        });
    }

    /** Step 4: TIMELINE_PLANNED -> ASSETS_BOUND; the style matrix must already be resolved. */
    public List<ReferenceAssetBinding> bindAssets() {
        return step(() -> {
            guard.assertCanTransition(ASSETS_BOUND);
            if (style == null) {
                throw new IllegalStateTransitionException("The style matrix must be resolved before binding assets.",
                        Map.of("current_state", guard.getCurrentState().name(), "target_state", ASSETS_BOUND.name()));
            }
            boundAssets = binder.bindAssets(intent.rawAssets(), intent.targetDurationSec());
            guard.transitionTo(ASSETS_BOUND);
            return boundAssets;
        });
    }

    /** Step 5: ASSETS_BOUND -> COMPILED. */
    public PromptPackage compile() {
        return step(() -> {
            guard.assertCanTransition(COMPILED);
            PromptPackage pkg = compiler.compile(intent.requestId(), intent.targetDurationSec(), intent.aspectRatio(),
                    style, timeline, boundAssets, intent.promptOptions());
            focusBudget = SceneAnalysis.focusBudget(intent.sanitizedDescription(), intent.suppressDetailAreas());
            guard.transitionTo(COMPILED);
            return pkg;
        });
    }

    public PromptPackage runToCompletion() {
        ingest();
        decompose();
        resolveStyle();
        bindAssets();
        return compile();
    }

    /** Fails the session without running a step (used for pre-flight rejections). */
    void abort(PromptEngineException ex) {
        step(() -> {
            throw ex;
        });
    }

    public EngineState state() {
        return guard.getCurrentState();
    }

    public List<PipelineStateGuard.Transition> history() {
        return guard.history();
    }

    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }

    /** Focus-budget decision behind the negative prompt (null until compiled). */
    public SceneAnalysis.FocusBudget focusBudget() {
        return focusBudget;
    }

    public ParsedIntentResult intent() {
        return intent;
    }

    public EngineState failedAt() {
        return failedAt;
    }

    public PromptEngineException error() {
        return error;
    }

    private void requireState(EngineState required, String operation) {
        if (guard.getCurrentState() != required) {
            throw new IllegalStateTransitionException("Cannot " + operation + " in state '" + guard.getCurrentState() + "'.",
                    Map.of("current_state", guard.getCurrentState().name(), "required_state", required.name()));
        }
    }

    private <T> T step(Supplier<T> body) {
        try {
            return body.get();
        } catch (PromptEngineException ex) {
            if (guard.canTransitionTo(FAILED)) {
                failedAt = guard.getCurrentState();
                error = ex;
                guard.transitionTo(FAILED, ex.errorCode());
            }
            throw ex;
        }
    }

    static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException | CancellationException ex) {
            if (ex.getCause() instanceof RuntimeException re) throw re;
            if (ex.getCause() instanceof Error err) throw err;
            throw ex;
        }
    }
}
