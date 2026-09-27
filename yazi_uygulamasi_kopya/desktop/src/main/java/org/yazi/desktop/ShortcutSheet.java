package org.yazi.desktop;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Locale;

/** The F1 cheat sheet, generated from {@link Keys#SHEET} so it always matches the real bindings. */
final class ShortcutSheet {

    private ShortcutSheet() {}

    /** Built but not shown. */
    static Dialog<Void> build(Window owner, UiSettings.Theme theme) {
        GridPane grid = new GridPane();
        grid.setHgap(18);
        grid.setVgap(5);
        grid.setPadding(new Insets(6, 20, 14, 20));
        ColumnConstraints action = new ColumnConstraints();
        action.setHgrow(Priority.ALWAYS);
        ColumnConstraints keys = new ColumnConstraints();
        keys.setHalignment(HPos.RIGHT);
        grid.getColumnConstraints().addAll(action, keys);

        int row = 0;
        String group = null;
        for (Keys.Entry entry : Keys.SHEET) {
            if (!entry.group().equals(group)) {
                group = entry.group();
                Label heading = new Label(group.toUpperCase(Locale.ROOT));
                heading.getStyleClass().add("sheet-group");
                grid.add(heading, 0, row++, 2, 1);
            }
            Label a = new Label(entry.action());
            a.getStyleClass().add("sheet-action");
            Label k = new Label(entry.keyText());
            k.getStyleClass().add("sheet-keys");
            grid.add(a, 0, row);
            grid.add(k, 1, row++);
        }

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(460);
        scroll.setPrefViewportWidth(600);
        scroll.getStyleClass().add("edge-to-edge");

        Label title = new Label("Keyboard shortcuts");
        title.getStyleClass().add("studio-title");
        Label subtitle = new Label("Everything in Yazı works from the keyboard.");
        subtitle.getStyleClass().add("studio-subtitle");
        Label glyph = new Label("⌨");
        glyph.getStyleClass().add("studio-glyph");
        HBox header = new HBox(12, glyph, new VBox(2, title, subtitle));
        header.getStyleClass().add("studio-header");

        Dialog<Void> dialog = new Dialog<>();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle("Keyboard shortcuts");
        dialog.setResizable(true);
        dialog.getDialogPane().setContent(new VBox(header, scroll));
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        return Styles.dialog(dialog, theme);
    }

    /** Number of shortcut rows (not group headings) in a built sheet; for tests. */
    static long rows(Dialog<Void> dialog) {
        VBox content = (VBox) dialog.getDialogPane().getContent();
        GridPane grid = (GridPane) ((ScrollPane) content.getChildren().get(1)).getContent();
        return grid.getChildren().stream().filter(n -> n.getStyleClass().contains("sheet-keys")).count();
    }
}
