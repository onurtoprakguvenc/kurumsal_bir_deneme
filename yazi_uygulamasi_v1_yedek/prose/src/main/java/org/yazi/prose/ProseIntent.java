package org.yazi.prose;

import org.yazi.model.Span;

/** What the writer asked for. Chosen by an explicit UI gesture (see {@link ProseRouter}), never guessed from wording. */
public sealed interface ProseIntent {

    /** The writer's instruction; may be empty, in which case a sensible default applies. */
    String instruction();

    /** Write new text at the caret. */
    record Continue(String instruction) implements ProseIntent {
        public Continue {
            instruction = (instruction == null) ? "" : instruction.strip();
        }
    }

    /** Replace {@code target} (a selection or a located block). With no instruction the passage is polished. */
    record Rewrite(Span target, String instruction) implements ProseIntent {
        public Rewrite {
            if (target == null || target.isEmpty()) {
                throw new IllegalArgumentException("A rewrite needs a non-empty target.");
            }
            instruction = (instruction == null) ? "" : instruction.strip();
        }
    }

    /** Ask about the text without changing it. */
    record Consult(String instruction, boolean wholeDocument) implements ProseIntent {
        public Consult {
            instruction = (instruction == null) ? "" : instruction.strip();
        }
    }
}
