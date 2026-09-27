package org.yazi.desktop;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleObjectProperty;
import org.yazi.gateway.CancellationToken;
import org.yazi.gateway.GatewayException;
import org.yazi.gateway.Usage;
import org.yazi.model.Span;
import org.yazi.model.Tier;
import org.yazi.prose.ProseIntent;
import org.yazi.prose.ProseOutcome;
import org.yazi.prose.ProsePipeline;
import org.yazi.prose.ProseRequest;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

/**
 * Runs one prose operation at a time and moves its results into the UI.
 *
 * <ul>
 *   <li>The pipeline runs on a virtual thread; the FX thread never waits on the network.</li>
 *   <li>Streamed fragments are queued and drained once per frame, not one {@code runLater} per token.</li>
 *   <li>Continue commits as soon as it finishes (one Ctrl+Z undoes it). Rewrite waits in review: Tab accepts,
 *       Esc rejects. Ask streams into the side panel and never touches the document.</li>
 *   <li>Every callback carries the id of the operation it belongs to; late results of a cancelled operation are
 *       ignored.</li>
 * </ul>
 *
 * <p>FX thread only, except for the worker it starts.</p>
 */
final class OperationController {

    enum State { IDLE, RUNNING, REVIEW }

    /** Where answers, status and cost go. Implemented by the main window; replaced by a recorder in tests. */
    interface View {
        void answerStarted(String question);

        void answerAppend(String fragment);

        void answerFinished(String text, boolean truncated);

        void status(String message);

        void cost(Usage usage, int windowChars);
    }

    private final EditorPane editor;
    private final ProsePipeline pipeline;
    private final View view;
    private final Supplier<String> brief;

    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(State.IDLE);
    private final ObjectProperty<Tier> tier = new SimpleObjectProperty<>(Tier.BALANCED);
    private final ConcurrentLinkedQueue<String> pending = new ConcurrentLinkedQueue<>();
    private final AnimationTimer pump;

    private long operationId;
    private CancellationToken token;
    private boolean streamingToDocument;
    private ProseOutcome.Edit review;

    OperationController(EditorPane editor, ProsePipeline pipeline, View view, Supplier<String> brief) {
        this.editor = Objects.requireNonNull(editor);
        this.pipeline = Objects.requireNonNull(pipeline);
        this.view = Objects.requireNonNull(view);
        this.brief = Objects.requireNonNull(brief);
        this.pump = new AnimationTimer() {
            @Override
            public void handle(long now) {
                drain();
            }
        };
    }

    ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    State state() {
        return state.get();
    }

    ObjectProperty<Tier> tierProperty() {
        return tier;
    }

    /** Starts an operation. Ignored unless idle. */
    void start(ProseIntent intent) {
        if (state.get() != State.IDLE) {
            return;
        }
        ProseRequest request = new ProseRequest(editor.snapshot(), intent, tier.get(), brief.get());
        long id = ++operationId;
        CancellationToken cancel = new CancellationToken();
        token = cancel;
        pending.clear();

        switch (intent) {
            case ProseIntent.Continue c -> beginDocumentPreview(Span.caret(request.snapshot().caret()), "Writing…");
            case ProseIntent.Rewrite r -> beginDocumentPreview(r.target(), "Rewriting…");
            case ProseIntent.Consult q -> {
                streamingToDocument = false;
                view.answerStarted(q.instruction());
                view.status("Thinking…  (Esc to cancel)");
            }
        }
        state.set(State.RUNNING);
        pump.start();

        Thread.ofVirtual().name("yazi-operation-" + id).start(() -> {
            try {
                ProseOutcome outcome = pipeline.run(request, pending::add, cancel);
                Platform.runLater(() -> finished(id, outcome));
            } catch (GatewayException e) {
                Platform.runLater(() -> failed(id, describe(e)));
            } catch (RuntimeException e) {
                Platform.runLater(() -> failed(id, e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        });
    }

    /** Esc: cancels a running operation or rejects a rewrite under review. */
    void cancelOrReject() {
        switch (state.get()) {
            case RUNNING -> {
                token.cancel();
                operationId++;   // late callbacks of the cancelled operation are ignored
                stopStreaming();
                editor.endPreview();
                state.set(State.IDLE);
                view.status("Cancelled.");
            }
            case REVIEW -> {
                editor.endPreview();
                review = null;
                state.set(State.IDLE);
                view.status("Rewrite discarded.");
            }
            case IDLE -> { }
        }
    }

    /** Tab: accepts a rewrite under review. */
    void accept() {
        if (state.get() != State.REVIEW) {
            return;
        }
        ProseOutcome.Edit edit = review;
        review = null;
        state.set(State.IDLE);
        apply(edit, "Rewrite applied.  (Ctrl+Z to undo)");
    }

    // ------------------------------------------------------------------------------------------------

    private void beginDocumentPreview(Span target, String message) {
        streamingToDocument = true;
        editor.beginPreview(target);
        view.status(message + "  (Esc to cancel)");
    }

    private void finished(long id, ProseOutcome outcome) {
        if (id != operationId) {
            return;
        }
        stopStreaming();
        view.cost(outcome.usage(), outcome.windowChars());

        switch (outcome) {
            case ProseOutcome.Answer answer -> {
                state.set(State.IDLE);
                view.answerFinished(answer.text(), answer.truncated());
                view.status(answer.truncated() ? "Answer cut off at the length limit." : "Answer ready.");
            }
            case ProseOutcome.Edit edit when edit.isEmpty() -> {
                editor.endPreview();
                state.set(State.IDLE);
                view.status("The model returned no text.");
            }
            case ProseOutcome.Edit edit when edit.replaced().isEmpty() -> {
                state.set(State.IDLE);
                apply(edit, edit.truncated() ? "Stopped at the length limit.  (Ctrl+Z to undo)" : "Done.  (Ctrl+Z to undo)");
            }
            case ProseOutcome.Edit edit -> {
                editor.showFinalGhost(edit.payload());
                review = edit;
                state.set(State.REVIEW);
                view.status("Tab: accept rewrite   Esc: discard");
            }
        }
    }

    private void failed(long id, String message) {
        if (id != operationId) {
            return;
        }
        stopStreaming();
        editor.endPreview();
        state.set(State.IDLE);
        view.status(message);
    }

    private void apply(ProseOutcome.Edit edit, String message) {
        // The editor is read-only during preview, so the revision cannot have moved; checked anyway so a future
        // change to that rule can never splice stale offsets.
        editor.endPreview();
        if (editor.revision() != edit.baseRevision()) {
            view.answerStarted("Could not insert: the document changed while the model was writing.");
            view.answerFinished(edit.payload(), edit.truncated());
            view.status("Document changed during generation; result moved to the side panel.");
            return;
        }
        editor.commit(edit.replaced(), edit.payload());
        view.status(message);
    }

    private void drain() {
        StringBuilder batch = new StringBuilder();
        String fragment;
        while ((fragment = pending.poll()) != null) {
            batch.append(fragment);
        }
        if (batch.isEmpty()) {
            return;
        }
        if (streamingToDocument) {
            editor.appendGhost(batch.toString());
        } else {
            view.answerAppend(batch.toString());
        }
    }

    private void stopStreaming() {
        drain();
        pump.stop();
        pending.clear();
    }

    private static String describe(GatewayException e) {
        return switch (e.kind()) {
            case CANCELLED -> "Cancelled.";
            case AUTH -> "The API key was rejected. Check it in File → API key…";
            case RATE_LIMITED -> "Rate limit reached" + e.retryAfter().map(d -> "; try again in " + d.toSeconds() + " s.").orElse(".");
            case BLOCKED -> "The provider blocked this request.";
            case NETWORK -> "Network problem: " + e.getMessage();
            default -> e.getMessage();
        };
    }
}
