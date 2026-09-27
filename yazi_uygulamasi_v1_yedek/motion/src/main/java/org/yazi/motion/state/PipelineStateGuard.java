package org.yazi.motion.state;

import org.yazi.motion.domain.EngineState;
import org.yazi.motion.domain.exception.IllegalStateTransitionException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.yazi.motion.domain.EngineState.*;

/**
 * Table-driven state machine (Stage 3). Every non-terminal state may move forward one step or to FAILED;
 * COMPILED and FAILED only lead back to UNINITIALIZED through {@link #reset()}.
 */
public final class PipelineStateGuard {

    public record Transition(EngineState from, EngineState to, String note) {
    }

    private static final Map<EngineState, Set<EngineState>> VALID_TRANSITIONS = new EnumMap<>(EngineState.class);

    static {
        VALID_TRANSITIONS.put(UNINITIALIZED, EnumSet.of(INGESTED, FAILED));
        VALID_TRANSITIONS.put(INGESTED, EnumSet.of(INTENT_PARSED, FAILED));
        VALID_TRANSITIONS.put(INTENT_PARSED, EnumSet.of(TIMELINE_PLANNED, FAILED));
        VALID_TRANSITIONS.put(TIMELINE_PLANNED, EnumSet.of(ASSETS_BOUND, FAILED));
        VALID_TRANSITIONS.put(ASSETS_BOUND, EnumSet.of(COMPILED, FAILED));
        VALID_TRANSITIONS.put(COMPILED, EnumSet.of(UNINITIALIZED));
        VALID_TRANSITIONS.put(FAILED, EnumSet.of(UNINITIALIZED));
    }

    private EngineState currentState = UNINITIALIZED;
    private final List<Transition> history = new ArrayList<>();

    public void transitionTo(EngineState targetState) {
        transitionTo(targetState, "ok");
    }

    public void transitionTo(EngineState targetState, String note) {
        assertCanTransition(targetState);
        history.add(new Transition(currentState, targetState, note));
        currentState = targetState;
    }

    public boolean canTransitionTo(EngineState targetState) {
        return VALID_TRANSITIONS.get(currentState).contains(targetState);
    }

    public void assertCanTransition(EngineState targetState) {
        if (!canTransitionTo(targetState)) {
            throw new IllegalStateTransitionException(
                    "Invalid state transition requested from '" + currentState + "' to '" + targetState + "'.",
                    Map.of("current_state", currentState.name(), "target_state", targetState.name()));
        }
    }

    public EngineState getCurrentState() {
        return currentState;
    }

    public void reset() {
        if (currentState != UNINITIALIZED) history.add(new Transition(currentState, UNINITIALIZED, "reset"));
        currentState = UNINITIALIZED;
    }

    public List<Transition> history() {
        return Collections.unmodifiableList(history);
    }
}
