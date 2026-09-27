package org.yazi.motion.domain;


public enum EngineState {
    UNINITIALIZED,
    INGESTED,
    INTENT_PARSED,
    TIMELINE_PLANNED,
    ASSETS_BOUND,
    COMPILED,
    FAILED
}
