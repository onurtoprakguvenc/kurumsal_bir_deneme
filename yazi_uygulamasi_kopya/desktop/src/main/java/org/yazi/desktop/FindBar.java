package org.yazi.desktop;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import org.fxmisc.richtext.StyleClassedTextArea;
import org.yazi.model.Span;

import java.util.List;

/**
 * Find bar above the instruction bar. Hidden until Ctrl+F.
 *
 * <pre>
 *   Ctrl+F               open (prefilled with a short one-line selection) and focus
 *   typing               jumps to the first match from where the search started
 *   Enter / Shift+Enter  next / previous match (wraps)
 *   F3 / Shift+F3        next / previous match from anywhere
 *   Esc                  close and return to the text; the match stays selected
 * </pre>
 *
 * <p>Only moves the selection; never edits the document. The main window disables it while an AI operation
 * runs, because the text then contains preview text that is not part of the document. FX thread only.</p>
 */
final class FindBar {

    static final String NO_MATCH = "no-match";

    private final StyleClassedTextArea area;
    private final HBox root;
    private final TextField query = new TextField();
    private final CheckBox matchCase = new CheckBox("Match case");
    private final Label counter = new Label();

    private List<Span> matches = List.of();
    private int anchor;

    FindBar(StyleClassedTextArea area) {
        this.area = area;

        query.setPromptText("Find   Enter: next   Shift+Enter: previous   Esc: close");
        query.setPrefColumnCount(28);
        HBox.setHgrow(query, Priority.ALWAYS);
        query.textProperty().addListener((obs, old, text) -> searchFromAnchor());
        query.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                if (e.isShiftDown()) {
                    previous();
                } else {
                    next();
                }
                e.consume();
            }
        });
        matchCase.selectedProperty().addListener((obs, old, v) -> searchFromAnchor());
        counter.getStyleClass().add("muted");
        counter.setMinWidth(90);

        Button previous = new Button("↑");
        previous.setTooltip(new Tooltip("Previous match (Shift+Enter, Shift+F3)"));
        previous.setOnAction(e -> previous());
        Button next = new Button("↓");
        next.setTooltip(new Tooltip("Next match (Enter, F3)"));
        next.setOnAction(e -> next());
        Button close = new Button("✕");
        close.setTooltip(new Tooltip("Close (Esc)"));
        close.setOnAction(e -> close());
        for (Button b : List.of(previous, next, close)) {
            b.setFocusTraversable(false);
            b.getStyleClass().add("find-button");
        }

        root = new HBox(6, query, counter, previous, next, matchCase, close);
        root.setAlignment(Pos.CENTER_LEFT);
        root.setPadding(new Insets(5, 10, 5, 10));
        root.getStyleClass().add("find-bar");
        setOpen(false);
    }

    HBox view() {
        return root;
    }

    boolean isOpen() {
        return root.isVisible();
    }

    boolean isFocused() {
        return query.isFocused() || matchCase.isFocused();
    }

    void setDisable(boolean disable) {
        root.setDisable(disable);
    }

    /** Shows the bar, prefilled with the selection when it is a short single line, and focuses the query. */
    void open() {
        String selected = area.getSelectedText();
        anchor = area.getSelection().getStart();
        setOpen(true);
        if (!selected.isEmpty() && selected.length() <= 100 && selected.indexOf('\n') < 0
                && !selected.equals(query.getText())) {
            query.setText(selected);   // triggers a search from the anchor, which re-selects this occurrence
        } else {
            refresh();
        }
        query.requestFocus();
        query.selectAll();
    }

    void close() {
        setOpen(false);
        area.requestFocus();
    }

    /** Selects the next match after the selection, wrapping. Opens the bar first if there is nothing to find. */
    void next() {
        if (query.getText().isEmpty()) {
            open();
            return;
        }
        refresh();
        select(TextFinder.nextIndex(matches, area.getSelection().getEnd()));
    }

    void previous() {
        if (query.getText().isEmpty()) {
            open();
            return;
        }
        refresh();
        select(TextFinder.previousIndex(matches, area.getSelection().getStart()));
    }

    /** Recomputes the matches after the document changed, without moving the selection. */
    void refresh() {
        matches = TextFinder.findAll(area.getText(), query.getText(), matchCase.isSelected());
        updateCounter();
    }

    // For tests.
    TextField queryField() {
        return query;
    }

    CheckBox matchCaseBox() {
        return matchCase;
    }

    String counterText() {
        return counter.getText();
    }

    // ------------------------------------------------------------------------------------------------

    private void searchFromAnchor() {
        refresh();
        if (!matches.isEmpty()) {
            select(TextFinder.nextIndex(matches, anchor));
        }
    }

    private void select(int index) {
        if (index < 0) {
            updateCounter();
            return;
        }
        Span match = matches.get(index);
        area.selectRange(match.start(), match.end());
        area.requestFollowCaret();
        updateCounter();
    }

    private void updateCounter() {
        var selection = area.getSelection();
        int current = TextFinder.indexOf(matches, new Span(selection.getStart(), selection.getEnd()));
        boolean empty = query.getText().isEmpty();
        counter.setText(TextFinder.counter(matches, current, empty));
        query.getStyleClass().remove(NO_MATCH);
        if (!empty && matches.isEmpty()) {
            query.getStyleClass().add(NO_MATCH);
        }
    }

    private void setOpen(boolean open) {
        root.setVisible(open);
        root.setManaged(open);
    }
}
