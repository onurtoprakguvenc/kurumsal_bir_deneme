package org.yazi.motion.pipeline;

import org.yazi.motion.domain.EngineState;
import org.yazi.motion.domain.PromptPackage;
import org.yazi.motion.domain.exception.PromptEngineException;
import org.yazi.motion.engines.SceneAnalysis;
import org.yazi.motion.state.PipelineStateGuard;

import java.util.List;
import java.util.Map;

public sealed interface PipelineResult {

    List<PipelineStateGuard.Transition> history();

    List<String> warnings();

    EngineState finalState();

    record Success(PromptPackage promptPackage, List<String> warnings,
                   List<PipelineStateGuard.Transition> history, SceneAnalysis.FocusBudget focusBudget) implements PipelineResult {
        public Success {
            warnings = List.copyOf(warnings);
            history = List.copyOf(history);
        }

        public EngineState finalState() {
            return EngineState.COMPILED;
        }
    }

    record Failure(PromptEngineException exception, EngineState failedAt, List<String> warnings,
                   List<PipelineStateGuard.Transition> history) implements PipelineResult {
        public Failure {
            warnings = List.copyOf(warnings);
            history = List.copyOf(history);
        }

        public String errorCode() {
            return exception.errorCode();
        }

        public String message() {
            return exception.getMessage();
        }

        public Map<String, Object> details() {
            return exception.details();
        }

        public EngineState finalState() {
            return EngineState.FAILED;
        }
    }
}
