package org.yazi.desktop;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yazi.gateway.GatewayException;
import org.yazi.gateway.ScriptedGateway;
import org.yazi.gateway.Usage;
import org.yazi.model.Span;
import org.yazi.prose.ModelCatalog;
import org.yazi.prose.ProseIntent;
import org.yazi.prose.ProsePipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;
import static org.yazi.desktop.OperationController.State.IDLE;
import static org.yazi.desktop.OperationController.State.REVIEW;
import static org.yazi.desktop.OperationController.State.RUNNING;

/**
 * End-to-end behaviour of the editor's AI flow on the real JavaFX toolkit: ghost preview, commit, review,
 * cancel, and above all what the undo history contains afterwards.
 */
class OperationControllerTest {

    private ScriptedGateway gateway;
    private EditorPane editor;
    private OperationController controller;
    private RecordingView view;

    @BeforeAll
    static void toolkit() {
        Fx.start();
    }

    @BeforeEach
    void setUp() {
        gateway = new ScriptedGateway();
        view = new RecordingView();
        Fx.run(() -> {
            editor = new EditorPane();
            controller = new OperationController(editor, new ProsePipeline(gateway, ModelCatalog.defaults()),
                    view, () -> "");
        });
    }

    private String text() {
        return Fx.call(() -> editor.area().getText());
    }

    private void awaitState(OperationController.State expected) {
        Fx.await("state " + expected, () -> controller.state() == expected);
    }

    // --- continue -------------------------------------------------------------------------------

    @Test
    void continuationCommitsAsOneUndoStep() {
        gateway.reply("Then ", "he left.");
        Fx.run(() -> {
            editor.load("She ran.");
            editor.area().moveTo(8);
            controller.start(new ProseIntent.Continue(""));
        });
        awaitState(IDLE);

        assertEquals("She ran. Then he left.", text());
        Fx.run(() -> editor.area().undo());
        assertEquals("She ran.", text());
        assertFalse(Fx.call(() -> editor.area().isUndoAvailable()), "ghost text leaked into the undo history");
        assertEquals(List.of(100), view.promptTokens);
    }

    @Test
    void ghostTextStreamsInReadOnlyEditorAndCancelRestoresEverything() {
        CountDownLatch release = new CountDownLatch(1);
        gateway.reply("Then ", "he left.").stallAfterFirstFragment(release);
        long revisionBefore = Fx.call(() -> {
            editor.load("She ran.");
            editor.area().moveTo(8);
            long r = editor.revision();
            controller.start(new ProseIntent.Continue(""));
            return r;
        });

        Fx.await("ghost text visible", () -> editor.area().getText().equals("She ran.Then "));
        assertEquals(RUNNING, Fx.call(controller::state));
        assertFalse(Fx.call(() -> editor.area().isEditable()));
        assertEquals(revisionBefore, Fx.call(editor::revision), "preview must not count as an edit");

        Fx.run(controller::cancelOrReject);
        release.countDown();

        assertEquals("She ran.", text());
        assertTrue(Fx.call(() -> editor.area().isEditable()));
        assertFalse(Fx.call(() -> editor.area().isUndoAvailable()));
        assertEquals(IDLE, Fx.call(controller::state));
        assertEquals("Cancelled.", view.lastStatus());
    }

    // --- rewrite --------------------------------------------------------------------------------

    @Test
    void rewriteWaitsForReviewThenAcceptsAsOneUndoStep() {
        gateway.reply("tired");
        Fx.run(() -> {
            editor.load("The old dog slept.");
            controller.start(new ProseIntent.Rewrite(new Span(4, 7), ""));
        });
        awaitState(REVIEW);

        assertEquals("The oldtired dog slept.", text(), "target stays visible (struck) with the ghost after it");
        Fx.run(controller::accept);
        assertEquals("The tired dog slept.", text());
        assertEquals(IDLE, Fx.call(controller::state));

        Fx.run(() -> editor.area().undo());
        assertEquals("The old dog slept.", text());
        assertFalse(Fx.call(() -> editor.area().isUndoAvailable()));
    }

    @Test
    void rejectedRewriteLeavesDocumentAndHistoryUntouched() {
        gateway.reply("tired");
        Fx.run(() -> {
            editor.load("The old dog slept.");
            editor.area().appendText(" Typed.");      // one real edit in the history
            controller.start(new ProseIntent.Rewrite(new Span(4, 7), ""));
        });
        awaitState(REVIEW);
        Fx.run(controller::cancelOrReject);

        assertEquals("The old dog slept. Typed.", text());
        Fx.run(() -> editor.area().undo());
        assertEquals("The old dog slept.", text(), "undo must step over the writer's edit, not the preview");
        assertFalse(Fx.call(() -> editor.area().isUndoAvailable()));
    }

    @Test
    void acceptedEditDoesNotMergeWithTyping() {
        gateway.reply("Two.");
        Fx.run(() -> {
            editor.load("");
            editor.area().appendText("One.");
            controller.start(new ProseIntent.Continue(""));
        });
        awaitState(IDLE);
        assertEquals("One. Two.", text());

        Fx.run(() -> editor.area().undo());
        assertEquals("One.", text(), "the AI edit must be its own undo step");
    }

    // --- ask ------------------------------------------------------------------------------------

    @Test
    void askStreamsToPanelAndNeverTouchesDocument() {
        gateway.reply("It is ", "clear.");
        long revision = Fx.call(() -> {
            editor.load("Some text.");
            long r = editor.revision();
            controller.start(new ProseIntent.Consult("Is it clear?", false));
            return r;
        });
        awaitState(IDLE);

        assertEquals("Some text.", text());
        assertEquals(revision, Fx.call(editor::revision));
        assertEquals("It is clear.", view.answer);
        assertEquals("Is it clear?", view.question);
    }

    // --- failures -------------------------------------------------------------------------------

    @Test
    void failureRemovesPreviewAndReportsReadableMessage() {
        gateway.failWith(new GatewayException(GatewayException.Kind.AUTH, "No API key set."));
        Fx.run(() -> {
            editor.load("Text.");
            editor.area().moveTo(5);
            controller.start(new ProseIntent.Continue(""));
        });
        awaitState(IDLE);

        assertEquals("Text.", text());
        assertTrue(view.lastStatus().contains("API key"), view.lastStatus());
        assertTrue(Fx.call(() -> editor.area().isEditable()));
    }

    @Test
    void writersOwnEditsAdvanceTheRevision() {
        long[] revisions = Fx.call(() -> {
            editor.load("a");
            long before = editor.revision();
            editor.area().appendText("b");
            return new long[]{before, editor.revision()};
        });
        assertEquals(revisions[0] + 1, revisions[1]);
    }

    /** Records what the controller tells the window. Called on the FX thread only. */
    private static final class RecordingView implements OperationController.View {
        final List<String> statuses = new ArrayList<>();
        final List<Integer> promptTokens = new ArrayList<>();
        volatile String question;
        volatile String answer = "";

        @Override
        public void answerStarted(String q) {
            question = q;
            answer = "";
        }

        @Override
        public void answerAppend(String fragment) {
            answer += fragment;
        }

        @Override
        public void answerFinished(String text, boolean truncated) {
            answer = text;
        }

        @Override
        public void status(String message) {
            synchronized (statuses) {
                statuses.add(message);
            }
        }

        @Override
        public void cost(Usage usage, int windowChars) {
            promptTokens.add(usage.promptTokens());
        }

        String lastStatus() {
            synchronized (statuses) {
                return statuses.isEmpty() ? "" : statuses.get(statuses.size() - 1);
            }
        }
    }
}
