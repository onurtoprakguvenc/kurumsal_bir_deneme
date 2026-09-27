package org.yazi.desktop;

import javafx.scene.Parent;
import javafx.scene.control.Dialog;

import java.util.Objects;

/** Applies the stylesheet and the current theme to a window root or a dialog. FX thread only. */
final class Styles {

    private Styles() {}

    static final String SHEET = Objects.requireNonNull(Styles.class.getResource("yazi.css"), "yazi.css")
            .toExternalForm();

    /** Adds the stylesheet (once) and makes {@code theme} the only theme class on {@code root}. */
    static void theme(Parent root, UiSettings.Theme theme) {
        if (!root.getStylesheets().contains(SHEET)) {
            root.getStylesheets().add(SHEET);
        }
        for (UiSettings.Theme t : UiSettings.Theme.values()) {
            root.getStyleClass().remove(t.styleClass());
        }
        root.getStyleClass().add(theme.styleClass());
    }

    /**
     * Themes a dialog. A dialog's scene root is an internal wrapper, not the {@code DialogPane}, so the pane gets
     * the {@code root} class itself; that is where the colour tokens are defined.
     */
    static <D extends Dialog<?>> D dialog(D dialog, UiSettings.Theme theme) {
        var pane = dialog.getDialogPane();
        if (!pane.getStyleClass().contains("root")) {
            pane.getStyleClass().add("root");
        }
        theme(pane, theme);
        return dialog;
    }
}
