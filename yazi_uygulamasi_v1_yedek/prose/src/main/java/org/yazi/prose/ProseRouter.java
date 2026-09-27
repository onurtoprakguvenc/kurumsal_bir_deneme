package org.yazi.prose;

import org.yazi.model.BufferSnapshot;
import org.yazi.text.BlockLocator;
import org.yazi.text.DirectiveSyntax;

/**
 * Maps a UI gesture plus the buffer geometry to an intent.
 *
 * <p>The old {@code BufferGeometryResolver} guessed "question or edit?" from words like how/what/why, so
 * "describe what she sees" never reached the buffer. Here the writer says which one they mean: apply (write or
 * rewrite) or ask. Geometry then decides the rest: a selection is rewritten, otherwise a structural operator
 * ({@code s/a/b/}, {@code a -> b}) targets the paragraph before the caret, otherwise text is written at the
 * caret.</p>
 */
public final class ProseRouter {

    private ProseRouter() {}

    public enum Gesture {
        /** Change the document: continue at the caret, or rewrite the selection. */
        APPLY,
        /** Ask about the selection or the text around the caret. */
        ASK,
        /** Ask about the whole document (capped). */
        ASK_DOCUMENT
    }

    public static ProseIntent route(BufferSnapshot snapshot, String instruction, Gesture gesture) {
        return switch (gesture) {
            case ASK -> new ProseIntent.Consult(instruction, false);
            case ASK_DOCUMENT -> new ProseIntent.Consult(instruction, true);
            case APPLY -> {
                if (snapshot.hasSelection()) {
                    yield new ProseIntent.Rewrite(snapshot.selection(), instruction);
                }
                if (DirectiveSyntax.isStructuralOperator(instruction)) {
                    var block = BlockLocator.precedingBlock(snapshot.text(), snapshot.caret());
                    if (block.isPresent()) {
                        yield new ProseIntent.Rewrite(block.get(), instruction);
                    }
                }
                yield new ProseIntent.Continue(instruction);
            }
        };
    }
}
