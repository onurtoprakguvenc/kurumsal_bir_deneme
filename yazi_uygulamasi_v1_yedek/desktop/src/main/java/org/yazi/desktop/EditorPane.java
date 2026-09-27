package org.yazi.desktop;

import javafx.beans.property.ReadOnlyLongProperty;
import javafx.beans.property.ReadOnlyLongWrapper;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.StyleClassedTextArea;
import org.fxmisc.richtext.model.RichTextChange;
import org.fxmisc.richtext.util.UndoUtils;
import org.fxmisc.undo.UndoManager;
import org.fxmisc.undo.UndoManagerFactory;
import org.yazi.model.BufferSnapshot;
import org.yazi.model.Span;

import java.util.Collection;
import java.util.List;

/**
 * The text area plus the two things the AI flow needs from it: a revision counter and a preview mode.
 *
 * <p><b>Preview.</b> While a model streams, its text appears in place as grey "ghost" text (and a rewrite target
 * is struck through). The ghost is a real document change, but the undo manager is built on a filtered change
 * stream that drops everything emitted during preview. Preview is always fully rolled back before the final
 * text is committed as one ordinary change, so the undo history only ever sees: the writer's own edits, and one
 * atomic step per accepted AI edit.</p>
 *
 * <p>The editor is read-only during preview, so the writer cannot interleave edits with the ghost.</p>
 *
 * <p>FX thread only.</p>
 */
final class EditorPane {

    static final String GHOST = "ai-ghost";
    static final String TARGET = "ai-target";

    private final StyleClassedTextArea area = new StyleClassedTextArea();
    private final VirtualizedScrollPane<StyleClassedTextArea> scroller = new VirtualizedScrollPane<>(area);
    private final ReadOnlyLongWrapper revision = new ReadOnlyLongWrapper(0);
    private final UndoManager<List<RichTextChange<Collection<String>, String, Collection<String>>>> undo;

    private boolean previewing;
    private Span previewTarget;
    private int ghostStart;
    private int ghostLength;

    EditorPane() {
        area.setWrapText(true);
        area.getStyleClass().add("yazi-editor");

        undo = UndoManagerFactory.unlimitedHistoryFactory().createMultiChangeUM(
                area.multiRichChanges().filter(changes -> !previewing),
                RichTextChange::invert,
                UndoUtils.applyMultiRichTextChange(area),
                (a, b) -> a.mergeWith(b),
                RichTextChange::isIdentity,
                UndoUtils.DEFAULT_PREVENT_MERGE_DELAY);
        area.setUndoManager(undo);

        area.plainTextChanges().filter(change -> !previewing && !change.isIdentity())
                .subscribe(change -> revision.set(revision.get() + 1));
    }

    StyleClassedTextArea area() {
        return area;
    }

    VirtualizedScrollPane<StyleClassedTextArea> view() {
        return scroller;
    }

    ReadOnlyLongProperty revisionProperty() {
        return revision.getReadOnlyProperty();
    }

    long revision() {
        return revision.get();
    }

    boolean isPreviewing() {
        return previewing;
    }

    BufferSnapshot snapshot() {
        var selection = area.getSelection();
        return new BufferSnapshot(revision.get(), area.getText(),
                Span.of(selection.getStart(), selection.getEnd()), area.getCaretPosition());
    }

    /** Replaces the whole document (open file / new file) and clears the undo history. */
    void load(String text) {
        area.replaceText(text);
        undo.forgetHistory();
        area.moveTo(0);
        area.requestFollowCaret();
    }

    // ------------------------------------------------------------------------------------------------
    // Preview
    // ------------------------------------------------------------------------------------------------

    /**
     * Starts a preview. An empty target is an insertion point; a non-empty target is struck through and the
     * ghost text appears right after it.
     */
    void beginPreview(Span target) {
        if (previewing) {
            throw new IllegalStateException("A preview is already active.");
        }
        Span t = target.clampTo(area.getLength());
        previewing = true;
        previewTarget = t;
        ghostStart = t.end();
        ghostLength = 0;
        area.setEditable(false);
        if (!t.isEmpty()) {
            area.setStyleClass(t.start(), t.end(), TARGET);
        }
    }

    void appendGhost(String fragment) {
        if (!previewing || fragment.isEmpty()) {
            return;
        }
        area.insert(ghostStart + ghostLength, fragment, GHOST);
        ghostLength += fragment.length();
        area.moveTo(ghostStart + ghostLength);
        area.requestFollowCaret();
    }

    /** Replaces the streamed ghost with the final, cleaned text, still as a preview. */
    void showFinalGhost(String text) {
        if (!previewing) {
            return;
        }
        area.replace(ghostStart, ghostStart + ghostLength, text, GHOST);
        ghostLength = text.length();
        area.moveTo(ghostStart + ghostLength);
        area.requestFollowCaret();
    }

    /** Removes the ghost and the target marking, leaving the document exactly as before the preview. */
    void endPreview() {
        if (!previewing) {
            return;
        }
        if (ghostLength > 0) {
            area.deleteText(ghostStart, ghostStart + ghostLength);
        }
        if (!previewTarget.isEmpty()) {
            area.setStyle(previewTarget.start(), previewTarget.end(), List.of());
        }
        area.moveTo(previewTarget.end());
        previewing = false;
        previewTarget = null;
        ghostLength = 0;
        area.setEditable(true);
    }

    /**
     * Ends any preview, then applies {@code payload} over {@code replaced} as one undoable step that never merges
     * with the writer's typing.
     */
    void commit(Span replaced, String payload) {
        endPreview();
        Span r = replaced.clampTo(area.getLength());
        undo.preventMerge();
        area.replaceText(r.start(), r.end(), payload);
        undo.preventMerge();
        area.moveTo(r.start() + payload.length());
        area.requestFollowCaret();
    }
}
