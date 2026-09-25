package org.example.ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.example.repl.InternalTerminalEngine;
import org.example.repl.InternalTerminalEngine.LineBuffer;
import org.example.state.Subscription;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The {@code TERMINAL} panel: an incremental, virtualized mirror of the terminal's {@link LineBuffer} plus a command
 * input.
 *
 * <p>Lines arrive on whatever thread produced them and are queued; one coalesced FX pulse appends the whole batch and
 * trims the mirror to the ring's capacity, so the UI never holds more lines than the engine does and a chatty command
 * costs one layout pass per frame rather than one per line. Sequence numbers make the initial snapshot and the live
 * stream join without gaps or duplicates.</p>
 */
public final class TerminalPanelView extends VBox {

    /** A queued buffer event: a line, or a clear marker ({@code text == null}). */
    private record Event(long sequence, String text) {
    }

    private final ObservableList<String> lines = FXCollections.observableArrayList();
    private final ListView<String> output = new ListView<>(lines);
    private final TextField input = new TextField();
    private final Label title = new Label("TERMİNAL");
    private final ConcurrentLinkedQueue<Event> queue = new ConcurrentLinkedQueue<>();
    private final Fx.Coalescer drain = new Fx.Coalescer(this::drain);

    private InternalTerminalEngine engine;
    private Subscription subscription;
    private int capacity = 1;
    private long seenUpTo;
    private int historyCursor;
    private Runnable onHide = () -> { };

    public TerminalPanelView() {
        getStyleClass().add("terminal-panel");
        setSpacing(0);

        title.getStyleClass().add("section-title");
        Button clear = new Button("Temizle");
        clear.getStyleClass().add("ghost-button");
        clear.setOnAction(e -> {
            if (engine != null) {
                engine.buffer().clear();
            }
        });
        Button hide = new Button("✕");
        hide.getStyleClass().add("ghost-button");
        hide.setOnAction(e -> onHide.run());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, title, spacer, clear, hide);
        bar.getStyleClass().add("panel-header");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 10, 6, 12));

        output.getStyleClass().add("terminal-output");
        output.setFocusTraversable(false);
        output.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String line, boolean empty) {
                super.updateItem(line, empty);
                setText(empty ? null : line);
            }
        });
        VBox.setVgrow(output, Priority.ALWAYS);

        input.getStyleClass().add("terminal-input");
        input.setPromptText("komut  (help ile listele)");
        input.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);

        getChildren().addAll(bar, output, input);
    }

    private void onKey(KeyEvent e) {
        if (engine == null || e.isControlDown() || e.isAltDown() || e.isMetaDown()) {
            return;
        }
        if (e.getCode() == KeyCode.ENTER) {
            String line = input.getText();
            input.clear();
            if (!line.isBlank()) {
                // The engine echoes the line itself, redacted for sensitive commands such as 'admin unlock'.
                engine.submit(line);
            }
            historyCursor = Integer.MAX_VALUE;
            e.consume();
        } else if (e.getCode() == KeyCode.UP || e.getCode() == KeyCode.DOWN) {
            List<String> history = engine.history();
            if (!history.isEmpty()) {
                int next = Math.min(historyCursor, history.size()) + (e.getCode() == KeyCode.UP ? -1 : 1);
                historyCursor = Math.max(0, Math.min(history.size(), next));
                input.setText(historyCursor == history.size() ? "" : history.get(historyCursor));
                input.end();
            }
            e.consume();
        }
    }

    // ================================================================== binding

    /** Mirrors {@code terminal}'s buffer from now on; detaches from the previous terminal first. */
    public void bind(InternalTerminalEngine terminal) {
        unbind();
        this.engine = Objects.requireNonNull(terminal);
        LineBuffer buffer = terminal.buffer();
        capacity = buffer.capacity();
        subscription = buffer.subscribe(new InternalTerminalEngine.OutputListener() {
            @Override
            public void line(long sequence, String text) {
                queue.add(new Event(sequence, text));
                drain.request();
            }

            @Override
            public void cleared() {
                queue.add(new Event(-1, null));
                drain.request();
            }
        });
        // The buffer's monitor makes snapshot + sequence one atomic read; queued lines below seenUpTo are skipped.
        List<String> snapshot;
        synchronized (buffer) {
            snapshot = buffer.snapshot();
            seenUpTo = buffer.nextSequence();
        }
        lines.setAll(snapshot);
        historyCursor = Integer.MAX_VALUE;
        scrollToEnd();
    }

    public void unbind() {
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
        engine = null;
        queue.clear();
        lines.clear();
    }

    private void drain() {
        if (engine == null) {
            queue.clear();
            return;
        }
        List<String> batch = new ArrayList<>();
        Event event;
        while ((event = queue.poll()) != null) {
            if (event.text() == null) {
                batch.clear();
                lines.clear();
            } else if (event.sequence() >= seenUpTo) {
                batch.add(event.text());
                seenUpTo = event.sequence() + 1;
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        int overflow = lines.size() + batch.size() - capacity;
        if (overflow >= lines.size()) {
            lines.setAll(batch.subList(Math.max(0, batch.size() - capacity), batch.size()));
        } else {
            if (overflow > 0) {
                lines.remove(0, overflow);
            }
            lines.addAll(batch);
        }
        scrollToEnd();
    }

    private void scrollToEnd() {
        if (!lines.isEmpty()) {
            output.scrollTo(lines.size() - 1);
        }
    }

    // ================================================================== API

    public void setOnHide(Runnable action) {
        this.onHide = Objects.requireNonNull(action);
    }

    public void focusInput() {
        input.requestFocus();
    }

    /** Retained UI lines (never more than the buffer's capacity). */
    public int retainedLines() {
        return lines.size();
    }
}
