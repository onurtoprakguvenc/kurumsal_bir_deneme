package org.yazi.desktop;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yazi.text.ContextWindowPolicy;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Segmented control, studio dialog frame, styling helper and side panel, on the real toolkit. */
class UiComponentsTest {

    @BeforeAll
    static void toolkit() {
        Fx.start();
    }

    static void press(Node target, KeyCode code, boolean shift, boolean control) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, control, false, false));
    }

    // --- Segmented -------------------------------------------------------------------------------

    enum Size { S, M, L }

    @Test
    void segmentedKeepsButtonsAndValueInSync() {
        Fx.run(() -> {
            Segmented<Size> seg = new Segmented<>(List.of(Size.values()), Size::name, Size.M);
            assertEquals(Size.M, seg.getValue());
            assertTrue(seg.button(Size.M).isSelected());

            seg.button(Size.L).fire();
            assertEquals(Size.L, seg.getValue());
            seg.setValue(Size.S);
            assertTrue(seg.button(Size.S).isSelected());
            assertFalse(seg.button(Size.L).isSelected());
            assertTrue(seg.view().getStyleClass().contains("segmented"));
        });
    }

    @Test
    void segmentedNeverLosesItsValue() {
        Fx.run(() -> {
            Segmented<Size> seg = new Segmented<>(List.of(Size.values()), Size::name, null);
            assertEquals(Size.S, seg.getValue(), "null initial falls back to the first item");
            seg.button(Size.S).setSelected(false);   // what clicking the selected segment does
            assertEquals(Size.S, seg.getValue());
            assertTrue(seg.button(Size.S).isSelected());
        });
    }

    @Test
    void segmentedArrowKeysMoveTheSelectionAndStopAtTheEnds() {
        Fx.run(() -> {
            Segmented<Size> seg = new Segmented<>(List.of(Size.values()), Size::name, Size.S);
            press(seg.view(), KeyCode.RIGHT, false, false);
            assertEquals(Size.M, seg.getValue());
            press(seg.view(), KeyCode.END, false, false);
            assertEquals(Size.L, seg.getValue());
            press(seg.view(), KeyCode.RIGHT, false, false);
            assertEquals(Size.L, seg.getValue());
            press(seg.view(), KeyCode.HOME, false, false);
            assertEquals(Size.S, seg.getValue());
            press(seg.view(), KeyCode.LEFT, false, false);
            assertEquals(Size.S, seg.getValue());
        });
    }

    @Test
    void segmentedNeedsItems() {
        Fx.run(() -> assertThrows(IllegalArgumentException.class,
                () -> new Segmented<Size>(List.of(), Size::name, null)));
    }

    // --- StudioDialog ----------------------------------------------------------------------------

    private static Dialog<String> studio(TextField field, UiSettings.Theme theme) {
        return new StudioDialog<String>("✦", "Title", "Subtitle", "Compile")
                .badge("Local · 0 tokens", true)
                .stepper(List.of("One", "Two", "Three"), 1)
                .section("Input")
                .field("_Name", field, "hint")
                .validWhen(field.textProperty().isNotEmpty(), new ReadOnlyStringWrapper("Name required."))
                .result(field::getText)
                .focus(field)
                .build(null, theme);
    }

    /** The primary button; looked up by type because an unshown ButtonBar has not parented its buttons yet. */
    static Button run(Dialog<?> d) {
        return (Button) d.getDialogPane().lookupButton(d.getDialogPane().getButtonTypes().get(0));
    }

    private static Label validation(Dialog<?> d) {
        return (Label) d.getDialogPane().lookup("#studio-validation");
    }

    @Test
    void studioDialogDisablesItsActionUntilValidAndExplainsWhy() {
        Fx.run(() -> {
            TextField field = new TextField();
            Dialog<String> d = studio(field, UiSettings.Theme.LIGHT);
            assertTrue(run(d).isDisabled());
            assertEquals("Name required.", validation(d).getText());
            field.setText("Ada");
            assertFalse(run(d).isDisabled());
            assertEquals("", validation(d).getText());
            assertTrue(run(d).getStyleClass().contains("primary"));
            assertEquals(ButtonBar.ButtonData.OK_DONE, d.getDialogPane().getButtonTypes().get(0).getButtonData());
        });
    }

    @Test
    void ctrlEnterRunsTheDialogOnlyWhenValid() {
        Fx.run(() -> {
            TextField field = new TextField();
            Dialog<String> d = studio(field, UiSettings.Theme.LIGHT);
            press(d.getDialogPane(), KeyCode.ENTER, false, true);
            assertNull(d.getResult(), "invalid: Ctrl+Enter must do nothing");

            field.setText("Ada");
            press(field, KeyCode.ENTER, false, true);
            assertEquals("Ada", d.getResult());
        });
    }

    @Test
    void studioDialogStructure() {
        Fx.run(() -> {
            TextField field = new TextField();
            Dialog<String> d = studio(field, UiSettings.Theme.DARK);
            var pane = d.getDialogPane();
            assertTrue(pane.getStyleClass().contains("root"), "tokens are defined on .root");
            assertTrue(pane.getStyleClass().contains("theme-dark"));
            assertTrue(pane.getStylesheets().contains(Styles.SHEET));

            Label label = (Label) pane.lookupAll(".studio-label").iterator().next();
            assertSame(field, label.getLabelFor(), "Alt+N must focus the field");
            assertTrue(label.isMnemonicParsing());

            List<Node> steps = List.copyOf(pane.lookupAll(".step"));
            assertEquals(3, steps.size());
            assertTrue(steps.get(0).getStyleClass().contains("done"));
            assertTrue(steps.get(1).getStyleClass().contains("active"));
            assertTrue(steps.get(2).getStyleClass().contains("pending"));
            assertTrue(((Label) pane.lookup(".badge")).getStyleClass().contains("local"));
            assertNotNull(pane.lookup(".studio-section"));
            assertTrue(pane.lookupAll(".studio-hint").stream()
                    .anyMatch(n -> ((Label) n).getText().contains("Ctrl+Enter to compile")));
        });
    }

    @Test
    void stylesSwapThemesWithoutStackingClassesOrSheets() {
        Fx.run(() -> {
            var box = new javafx.scene.layout.VBox();
            Styles.theme(box, UiSettings.Theme.DARK);
            Styles.theme(box, UiSettings.Theme.LIGHT);
            Styles.theme(box, UiSettings.Theme.LIGHT);
            assertEquals(1, box.getStyleClass().stream().filter(c -> c.startsWith("theme-")).count());
            assertTrue(box.getStyleClass().contains("theme-light"));
            assertEquals(1, box.getStylesheets().size());
        });
    }

    // --- SidePanel -------------------------------------------------------------------------------

    @Test
    void answersShowTheQuestionAndAWordCount() {
        Fx.run(() -> {
            SidePanel p = new SidePanel();
            assertTrue(p.copyButton().isDisabled());
            assertTrue(p.clearButton().isDisabled());

            p.answerStarted("  Is   the ending clear? ");
            assertEquals("Is the ending clear?", p.heading());
            p.answerAppend("Mostly ");
            p.answerAppend("yes.");
            assertEquals("Mostly yes.", p.answerArea().getText());
            assertEquals("2 words", p.answerMeta());
            assertFalse(p.copyButton().isDisabled());

            p.answerFinished("Cut", true);
            assertTrue(p.answerArea().getText().endsWith("[cut off at the length limit]"));
        });
    }

    @Test
    void toolOutputIsMonospaceUntilTheNextAnswer() {
        Fx.run(() -> {
            SidePanel p = new SidePanel();
            p.showOutput("Video prompt", "POSITIVE\n...");
            assertTrue(p.isMonospace());
            assertEquals("Video prompt", p.heading());
            p.answerStarted("Question");
            assertFalse(p.isMonospace());
        });
    }

    @Test
    void copyPutsTheAnswerOnTheClipboardAndConfirms() {
        Fx.run(() -> {
            SidePanel p = new SidePanel();
            p.answerFinished("Kopyalanacak metin", false);
            p.copyButton().fire();
            assertEquals("Kopyalanacak metin", Clipboard.getSystemClipboard().getString());
            assertTrue(p.copyButton().getText().startsWith("Copied"));
        });
    }

    @Test
    void clearEmptiesTheCard() {
        Fx.run(() -> {
            SidePanel p = new SidePanel();
            p.showOutput("Image prompt", "text");
            p.clearButton().fire();
            assertEquals("", p.answerArea().getText());
            assertEquals("", p.heading());
            assertEquals("", p.answerMeta());
            assertFalse(p.isMonospace());
        });
    }

    @Test
    void notesCounterWarnsNearTheLimitAndTheLimitHolds() {
        Fx.run(() -> {
            SidePanel p = new SidePanel();
            int max = ContextWindowPolicy.BRIEF_MAX_CHARS;
            assertEquals(String.format("%,d / %,d", 0, max), p.notesCounter());
            p.notesArea().setText("x".repeat(max / 2));
            assertFalse(p.notesCounterWarns());
            p.notesArea().setText("x".repeat((int) (max * SidePanel.NOTES_WARNING_SHARE)));
            assertTrue(p.notesCounterWarns());
            p.notesArea().setText("x".repeat(max + 10));
            assertTrue(p.notes().length() <= max, "the limit is enforced");
        });
    }
}
