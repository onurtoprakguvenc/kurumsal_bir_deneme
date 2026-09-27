package org.yazi.visual;

/** How the scene text reaches the contract extractor (unchanged from VisualPromptApp). */
public enum IngestionMode {
    /** The text is the scene. */
    DIRECT,
    /** The text is a narrative sequence; one keyframe is isolated first (one extra model call). */
    NARRATIVE,
    /** Three slots (environment, action, directives) are assembled locally, without a model call. */
    THREE_STAGE
}
