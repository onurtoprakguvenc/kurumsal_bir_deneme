package org.yazi.motion.pipeline;

import org.yazi.motion.domain.EngineState;
import org.yazi.motion.domain.PromptPackage;
import org.yazi.motion.domain.RawUserPromptInput;
import org.yazi.motion.domain.exception.IllegalStateTransitionException;
import org.yazi.motion.domain.exception.PromptEngineException;
import org.yazi.motion.engines.CinematicStyleMatrixEngine;
import org.yazi.motion.engines.ICinematicStyleMatrixEngine;
import org.yazi.motion.engines.IPromptPackageCompiler;
import org.yazi.motion.engines.IReferenceAssetBinder;
import org.yazi.motion.engines.ITemporalKinematicDecomposer;
import org.yazi.motion.engines.IUserIntentIngestionEngine;
import org.yazi.motion.engines.PromptPackageCompiler;
import org.yazi.motion.engines.ReferenceAssetBinder;
import org.yazi.motion.engines.TemporalKinematicDecomposer;
import org.yazi.motion.engines.UserIntentIngestionEngine;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * Build step 09. Each execution gets its own {@link PipelineSession} and state guard, so the orchestrator is safe to
 * share between threads; {@link #getPipelineStatus()} reports the most recently started execution.
 * A request_id that ended in FAILED cannot be executed again on this orchestrator (Stage-1 terminal-state rule).
 * No network I/O is performed.
 */
public final class PromptPipelineOrchestrator {

    private static final int FAILED_ID_MEMORY = 10_000;

    private final IUserIntentIngestionEngine ingestionEngine;
    private final ITemporalKinematicDecomposer decompositionEngine;
    private final ICinematicStyleMatrixEngine styleEngine;
    private final Function<List<String>, IReferenceAssetBinder> binderFactory;
    private final IPromptPackageCompiler compilerEngine;

    private final Map<String, Boolean> failedRequestIds = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > FAILED_ID_MEMORY;
                }
            });
    private volatile PipelineSession lastSession;

    public PromptPipelineOrchestrator() {
        this(Clock.systemUTC());
    }

    public PromptPipelineOrchestrator(Clock clock) {
        this(new UserIntentIngestionEngine(), new TemporalKinematicDecomposer(), new CinematicStyleMatrixEngine(),
                warnings -> new ReferenceAssetBinder(warnings::add), new PromptPackageCompiler(clock));
    }

    /** @param binderFactory receives the run's warning list so automatic repairs are reported */
    public PromptPipelineOrchestrator(IUserIntentIngestionEngine ingestionEngine, ITemporalKinematicDecomposer decompositionEngine,
                                      ICinematicStyleMatrixEngine styleEngine,
                                      Function<List<String>, IReferenceAssetBinder> binderFactory,
                                      IPromptPackageCompiler compilerEngine) {
        this.ingestionEngine = ingestionEngine;
        this.decompositionEngine = decompositionEngine;
        this.styleEngine = styleEngine;
        this.binderFactory = binderFactory;
        this.compilerEngine = compilerEngine;
    }

    /** Stage-3 API: completes with the package, or exceptionally with the PromptEngineException that failed the run. */
    public CompletableFuture<PromptPackage> execute(RawUserPromptInput input) {
        return switch (run(input)) {
            case PipelineResult.Success s -> CompletableFuture.completedFuture(s.promptPackage());
            case PipelineResult.Failure f -> CompletableFuture.failedFuture(f.exception());
        };
    }

    /** Synchronous variant that never throws for pipeline errors; includes warnings and the state history. */
    public PipelineResult run(RawUserPromptInput input) {
        PipelineSession session = newSession(input);
        lastSession = session;
        String requestId = input == null || input.requestId() == null ? null : input.requestId().trim();
        try {
            if (requestId != null && failedRequestIds.containsKey(requestId)) {
                session.abort(new IllegalStateTransitionException("Request '" + requestId
                        + "' already ended in FAILED; start a new run with a new request_id.",
                        Map.of("request_id", requestId, "current_state", EngineState.FAILED.name())));
            }
            PromptPackage pkg = session.runToCompletion();
            return new PipelineResult.Success(pkg, session.warnings(), session.history(), session.focusBudget());
        } catch (PromptEngineException ex) {
            if (requestId != null && !requestId.isEmpty()) failedRequestIds.put(requestId, Boolean.TRUE);
            return new PipelineResult.Failure(ex, session.failedAt(), session.warnings(), session.history());
        }
    }

    public CompletableFuture<PipelineResult> runAsync(RawUserPromptInput input, Executor executor) {
        return CompletableFuture.supplyAsync(() -> run(input), executor);
    }

    /** A step-by-step session (not tracked by getPipelineStatus). */
    public PipelineSession newSession(RawUserPromptInput input) {
        List<String> warnings = new ArrayList<>();
        return new PipelineSession(input, ingestionEngine, decompositionEngine, styleEngine,
                binderFactory.apply(warnings), compilerEngine, warnings);
    }

    public EngineState getPipelineStatus() {
        PipelineSession s = lastSession;
        return s == null ? EngineState.UNINITIALIZED : s.state();
    }
}
