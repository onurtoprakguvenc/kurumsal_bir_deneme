package org.yazi.desktop;

import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableBooleanValue;
import javafx.beans.value.ObservableStringValue;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The shared frame of the app's tool dialogs (Compile, meta-prompt stages, API key): a header with a glyph, title,
 * subtitle and badge; an optional stage stepper; a two-column form with section headings; a validation line; and
 * a footer with keyboard hints.
 *
 * <p>Keyboard: Ctrl+Enter runs the primary action from anywhere (Enter alone still adds a line in text areas),
 * Esc cancels, Alt + the underlined letter of a label jumps to its field. The primary button is disabled while
 * {@link #validWhen} reports a problem.</p>
 *
 * <p>{@link #build} returns the dialog without showing it, which is what the tests inspect. FX thread only.</p>
 */
final class StudioDialog<T> {

    private final String glyph;
    private final String title;
    private final String subtitle;
    private final String action;
    private final GridPane form = new GridPane();
    private int row;
    private Node stepper;
    private String badge;
    private boolean localBadge;
    private ObservableBooleanValue valid;
    private ObservableStringValue problem;
    private Supplier<T> result;
    private Node initialFocus;

    StudioDialog(String glyph, String title, String subtitle, String action) {
        this.glyph = glyph;
        this.title = title;
        this.subtitle = subtitle;
        this.action = action;
        form.getStyleClass().add("studio-body");
        form.setHgap(14);
        form.setVgap(8);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(Region.USE_PREF_SIZE);
        labels.setHalignment(HPos.RIGHT);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        fields.setFillWidth(true);
        form.getColumnConstraints().addAll(labels, fields);
    }

    /** A small pill in the header, e.g. "Local · 0 tokens"; {@code local} colours it as free. */
    StudioDialog<T> badge(String text, boolean local) {
        this.badge = text;
        this.localBadge = local;
        return this;
    }

    /** "Decompose → Compile → Verify" with {@code active} highlighted and earlier steps marked done. */
    StudioDialog<T> stepper(List<String> steps, int active) {
        HBox box = new HBox(8);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("stepper");
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) {
                Label arrow = new Label("→");
                arrow.getStyleClass().add("step-arrow");
                box.getChildren().add(arrow);
            }
            String mark = i < active ? "✓ " : (i + 1) + " ";
            Label step = new Label(mark + steps.get(i));
            step.getStyleClass().addAll("step", i < active ? "done" : i == active ? "active" : "pending");
            box.getChildren().add(step);
        }
        this.stepper = box;
        return this;
    }

    StudioDialog<T> section(String name) {
        Label label = new Label(name.toUpperCase(Locale.ROOT));
        label.getStyleClass().add("studio-section");
        GridPane.setHalignment(label, HPos.LEFT);   // the label column is right-aligned; headings are not
        form.add(label, 0, row++, 2, 1);
        return this;
    }

    /**
     * A labelled field. An underscore in {@code label} marks its Alt mnemonic ("_Scene" → Alt+S focuses the
     * control). {@code hint} (may be null) is a muted line under the control.
     */
    StudioDialog<T> field(String label, Node control, Node hint) {
        Label l = new Label(label);
        l.setMnemonicParsing(true);
        l.setLabelFor(control);
        l.getStyleClass().add("studio-label");
        GridPane.setValignment(l, VPos.TOP);
        GridPane.setMargin(l, new Insets(5, 0, 0, 0));
        form.add(l, 0, row);
        if (hint == null) {
            form.add(control, 1, row++);
        } else {
            VBox box = new VBox(3, control, hint);
            form.add(box, 1, row++);
        }
        GridPane.setHgrow(control, Priority.ALWAYS);
        return this;
    }

    StudioDialog<T> field(String label, Node control) {
        return field(label, control, (Node) null);
    }

    StudioDialog<T> field(String label, Node control, String hint) {
        return field(label, control, hint == null ? null : hintLabel(hint));
    }

    /** A full-width row without a label. */
    StudioDialog<T> wide(Node node) {
        form.add(node, 0, row++, 2, 1);
        return this;
    }

    /** Disables the primary button while {@code valid} is false and shows {@code problem} in the footer. */
    StudioDialog<T> validWhen(ObservableBooleanValue valid, ObservableStringValue problem) {
        this.valid = valid;
        this.problem = problem;
        return this;
    }

    StudioDialog<T> result(Supplier<T> result) {
        this.result = result;
        return this;
    }

    StudioDialog<T> focus(Node node) {
        this.initialFocus = node;
        return this;
    }

    Dialog<T> build(Window owner, UiSettings.Theme theme) {
        Dialog<T> dialog = new Dialog<>();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle(title);
        dialog.setResizable(true);

        VBox content = new VBox();
        content.getChildren().add(defaultHeader());
        content.getChildren().add(form);

        Label validation = new Label();
        validation.getStyleClass().addAll("validation", "invalid");
        validation.setId("studio-validation");
        Label keys = hintLabel("Ctrl+Enter to " + action.toLowerCase(Locale.ROOT) + "  ·  Esc to cancel");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(10, validation, spacer, keys);
        footer.getStyleClass().add("studio-footer");
        footer.setAlignment(Pos.CENTER_LEFT);
        content.getChildren().add(footer);

        var pane = dialog.getDialogPane();
        pane.setContent(content);
        pane.setMinWidth(560);
        ButtonType run = new ButtonType(action, ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().addAll(run, ButtonType.CANCEL);
        Button runButton = (Button) pane.lookupButton(run);
        runButton.getStyleClass().add("primary");
        runButton.setId("studio-run");
        if (valid != null) {
            runButton.disableProperty().bind(Bindings.not(valid));
            validation.textProperty().bind(Bindings.when(valid).then("")
                    .otherwise(problem == null ? Bindings.concat("Incomplete") : Bindings.concat(problem)));
        }
        dialog.setResultConverter(b -> b == run && result != null ? result.get() : null);

        pane.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (Keys.CONFIRM_DIALOG.match(e)) {
                if (!runButton.isDisabled()) {
                    runButton.fire();
                }
                e.consume();
            }
        });
        if (initialFocus != null) {
            dialog.setOnShown(e -> initialFocus.requestFocus());
        }
        return Styles.dialog(dialog, theme);
    }

    Optional<T> show(Window owner, UiSettings.Theme theme) {
        return build(owner, theme).showAndWait();
    }

    private Node defaultHeader() {
        Label g = new Label(glyph);
        g.getStyleClass().add("studio-glyph");
        Label t = new Label(title);
        t.getStyleClass().add("studio-title");
        Label s = new Label(subtitle);
        s.getStyleClass().add("studio-subtitle");
        s.setWrapText(true);
        VBox text = new VBox(2, t, s);
        if (stepper != null) {
            text.getChildren().add(stepper);
            VBox.setMargin(stepper, new Insets(6, 0, 0, 0));
        }
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(12, g, text);
        row.setAlignment(Pos.TOP_LEFT);
        if (badge != null) {
            Label b = new Label(badge);
            b.getStyleClass().add("badge");
            if (localBadge) {
                b.getStyleClass().add("local");
            }
            b.setMinWidth(Region.USE_PREF_SIZE);
            row.getChildren().add(b);
        }
        row.getStyleClass().add("studio-header");
        return row;
    }

    static Label hintLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("studio-hint");
        l.setWrapText(true);
        return l;
    }
}
