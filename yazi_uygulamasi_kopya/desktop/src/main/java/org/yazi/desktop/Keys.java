package org.yazi.desktop;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import java.util.List;

/**
 * Every keyboard shortcut in one place. Menus take their accelerators from here, the scene-wide key filter
 * matches against these, and the F1 cheat sheet is generated from {@link #SHEET}, so the three cannot drift apart.
 */
final class Keys {

    private Keys() {}

    private static KeyCombination shortcut(KeyCode code, KeyCombination.Modifier... more) {
        KeyCombination.Modifier[] all = new KeyCombination.Modifier[more.length + 1];
        all[0] = KeyCombination.SHORTCUT_DOWN;
        System.arraycopy(more, 0, all, 1, more.length);
        return new KeyCodeCombination(code, all);
    }

    // File
    static final KeyCombination NEW = shortcut(KeyCode.N);
    static final KeyCombination OPEN = shortcut(KeyCode.O);
    static final KeyCombination SAVE = shortcut(KeyCode.S);
    static final KeyCombination SAVE_AS = shortcut(KeyCode.S, KeyCombination.SHIFT_DOWN);
    // Edit
    static final KeyCombination UNDO = shortcut(KeyCode.Z);
    static final KeyCombination REDO = shortcut(KeyCode.Y);
    static final KeyCombination CUT = shortcut(KeyCode.X);
    static final KeyCombination COPY = shortcut(KeyCode.C);
    static final KeyCombination PASTE = shortcut(KeyCode.V);
    static final KeyCombination SELECT_ALL = shortcut(KeyCode.A);
    static final KeyCombination FIND = shortcut(KeyCode.F);
    static final KeyCombination FIND_NEXT = new KeyCodeCombination(KeyCode.F3);
    static final KeyCombination FIND_PREVIOUS = new KeyCodeCombination(KeyCode.F3, KeyCombination.SHIFT_DOWN);
    // View
    static final KeyCombination TOGGLE_THEME = shortcut(KeyCode.D, KeyCombination.SHIFT_DOWN);
    static final KeyCombination TOGGLE_SIDE_PANEL = shortcut(KeyCode.BACK_SLASH);
    static final KeyCombination ZOOM_IN = shortcut(KeyCode.EQUALS);
    static final KeyCombination ZOOM_OUT = shortcut(KeyCode.MINUS);
    static final KeyCombination ZOOM_RESET = shortcut(KeyCode.DIGIT0);
    // Assist
    static final KeyCombination WRITE = shortcut(KeyCode.ENTER);
    static final KeyCombination ASK = shortcut(KeyCode.ENTER, KeyCombination.SHIFT_DOWN);
    static final KeyCombination INSTRUCTION_BAR = shortcut(KeyCode.K);
    static final KeyCombination TIER_FAST = shortcut(KeyCode.DIGIT1, KeyCombination.SHIFT_DOWN);
    static final KeyCombination TIER_BALANCED = shortcut(KeyCode.DIGIT2, KeyCombination.SHIFT_DOWN);
    static final KeyCombination TIER_DEEP = shortcut(KeyCode.DIGIT3, KeyCombination.SHIFT_DOWN);
    // Navigation
    static final KeyCombination FOCUS_NEXT = new KeyCodeCombination(KeyCode.F6);
    static final KeyCombination FOCUS_PREVIOUS = new KeyCodeCombination(KeyCode.F6, KeyCombination.SHIFT_DOWN);
    static final KeyCombination FOCUS_EDITOR = shortcut(KeyCode.DIGIT1);
    static final KeyCombination FOCUS_INSTRUCTION = shortcut(KeyCode.DIGIT2);
    static final KeyCombination FOCUS_ANSWER = shortcut(KeyCode.DIGIT3);
    static final KeyCombination FOCUS_NOTES = shortcut(KeyCode.DIGIT4);
    // Help
    static final KeyCombination SHORTCUTS = new KeyCodeCombination(KeyCode.F1);
    // Dialogs
    static final KeyCombination CONFIRM_DIALOG = shortcut(KeyCode.ENTER);

    /** Every combination bound by the app; must be unique (checked by a test). */
    static final List<KeyCombination> ALL = List.of(NEW, OPEN, SAVE, SAVE_AS, UNDO, REDO, CUT, COPY, PASTE,
            SELECT_ALL, FIND, FIND_NEXT, FIND_PREVIOUS, TOGGLE_THEME, TOGGLE_SIDE_PANEL, ZOOM_IN, ZOOM_OUT,
            ZOOM_RESET, WRITE, ASK, INSTRUCTION_BAR, TIER_FAST, TIER_BALANCED, TIER_DEEP, FOCUS_NEXT,
            FOCUS_PREVIOUS, FOCUS_EDITOR, FOCUS_INSTRUCTION, FOCUS_ANSWER, FOCUS_NOTES, SHORTCUTS);

    /** One cheat-sheet line. {@code keys} is a combination, or a plain description for context-bound keys. */
    record Entry(String group, String action, KeyCombination combination, String keys) {

        Entry(String group, String action, KeyCombination combination) {
            this(group, action, combination, null);
        }

        Entry(String group, String action, String keys) {
            this(group, action, null, keys);
        }

        /** "Ctrl+Enter" rather than JavaFX's "Ctrl+↵", which is easy to misread in a list. */
        String keyText() {
            return combination != null ? combination.getDisplayText().replace("↵", "Enter") : keys;
        }
    }

    static final List<Entry> SHEET = List.of(
            new Entry("Writing", "Write at the caret / rewrite the selection", WRITE),
            new Entry("Writing", "Ask about the selection or nearby text", ASK),
            new Entry("Writing", "Accept a rewrite", "Tab"),
            new Entry("Writing", "Discard a rewrite / cancel a request", "Esc"),
            new Entry("Writing", "Instruction bar", INSTRUCTION_BAR),
            new Entry("Writing", "Earlier instructions (in the instruction bar)", "Up / Down"),
            new Entry("Writing", "Tier: Fast", TIER_FAST),
            new Entry("Writing", "Tier: Balanced", TIER_BALANCED),
            new Entry("Writing", "Tier: Deep", TIER_DEEP),
            new Entry("Find", "Find", FIND),
            new Entry("Find", "Next / previous match", "Enter / Shift+Enter"),
            new Entry("Find", "Next match from anywhere", FIND_NEXT),
            new Entry("Find", "Previous match from anywhere", FIND_PREVIOUS),
            new Entry("Navigate", "Next region: editor, find, instruction, answer, notes", FOCUS_NEXT),
            new Entry("Navigate", "Previous region", FOCUS_PREVIOUS),
            new Entry("Navigate", "Editor", FOCUS_EDITOR),
            new Entry("Navigate", "Instruction bar", FOCUS_INSTRUCTION),
            new Entry("Navigate", "Answer panel", FOCUS_ANSWER),
            new Entry("Navigate", "Pinned notes", FOCUS_NOTES),
            new Entry("Navigate", "Back to the text", "Esc"),
            new Entry("View", "Dark theme", TOGGLE_THEME),
            new Entry("View", "Show / hide side panel", TOGGLE_SIDE_PANEL),
            new Entry("View", "Zoom in / out", ZOOM_IN.getDisplayText() + " / " + ZOOM_OUT.getDisplayText()),
            new Entry("View", "Actual size", ZOOM_RESET),
            new Entry("File", "New", NEW),
            new Entry("File", "Open", OPEN),
            new Entry("File", "Save", SAVE),
            new Entry("File", "Save as", SAVE_AS),
            new Entry("Dialogs", "Run (Compile, Use key, next stage)", CONFIRM_DIALOG),
            new Entry("Dialogs", "Close", "Esc"),
            new Entry("Dialogs", "Jump to a field", "Alt + underlined letter"),
            new Entry("Help", "This sheet", SHORTCUTS));
}
