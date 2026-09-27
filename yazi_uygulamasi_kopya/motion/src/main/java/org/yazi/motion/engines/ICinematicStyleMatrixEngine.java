package org.yazi.motion.engines;

import org.yazi.motion.domain.StylePreferences;
import org.yazi.motion.domain.VisualStyleMatrix;

public interface ICinematicStyleMatrixEngine {
    /** Constructs a fully qualified VisualStyleMatrix from user overrides and inferred context. Both arguments may be null. */
    VisualStyleMatrix resolveStyleMatrix(StylePreferences explicitPreferences, String contextDescription);
}
