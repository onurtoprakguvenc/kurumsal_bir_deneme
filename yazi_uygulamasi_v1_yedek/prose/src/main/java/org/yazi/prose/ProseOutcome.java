package org.yazi.prose;

import org.yazi.gateway.Usage;
import org.yazi.model.Span;

/** Result of a prose operation. */
public sealed interface ProseOutcome {

    Usage usage();

    /** Characters of document text the model was shown. */
    int windowChars();

    /**
     * Replace {@code replaced} with {@code payload}. The payload is already sanitised and seam-adjusted against
     * the snapshot with revision {@code baseRevision}; apply it only if the editor is still at that revision.
     * An empty {@code replaced} span is an insertion.
     */
    record Edit(long baseRevision, Span replaced, String payload, boolean truncated, Usage usage, int windowChars)
            implements ProseOutcome {

        public boolean isEmpty() {
            return payload.isBlank();
        }
    }

    /** An answer for the side panel. The document is untouched, and the answer is never sent back to the model. */
    record Answer(String text, boolean truncated, Usage usage, int windowChars) implements ProseOutcome {
    }
}
