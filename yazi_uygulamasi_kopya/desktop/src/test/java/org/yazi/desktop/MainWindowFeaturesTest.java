package org.yazi.desktop;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yazi.model.Span;
import org.yazi.prose.ModelCatalog;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The window-level features on the real toolkit (window built, never shown): format-preserving open/save, the
 * find bar driven by key events, the word count, instruction history and the command-line file argument.
 */
class MainWindowFeaturesTest {

    private static final Charset CP1254 = Charset.forName("windows-1254");

    @TempDir
    Path dir;

    private Stage stage;
    private MainWindow window;
    private Scene scene;

    @BeforeAll
    static void toolkit() {
        Fx.start();
    }

    @BeforeEach
    void setUp() {
        Fx.run(() -> {
            stage = new Stage();
            window = new MainWindow(stage, new KeyedGateway(null), ModelCatalog.defaults());
            scene = window.buildScene();
            stage.setScene(scene);
            scene.getRoot().applyCss();   // creates the SplitPane skin, which is what parents the editor
        });
    }

    private static void press(Node target, KeyCode code, boolean shift, boolean control) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, control, false, false));
    }

    private String text() {
        return window.editor().area().getText();
    }

    private String selected() {
        return window.editor().area().getSelectedText();
    }

    // --- files -----------------------------------------------------------------------------------

    @Test
    void turkishCrlfFileOpensAndSavesBackInItsOwnFormat() throws IOException {
        Path file = dir.resolve("günlük.txt");
        Files.write(file, "Birinci satır\r\nİkinci satır\r\n".getBytes(CP1254));

        Fx.run(() -> {
            assertTrue(window.openPath(file));
            assertEquals("Birinci satır\nİkinci satır\n", text());
            assertEquals("Opened günlük.txt  (windows-1254, CRLF)", window.statusText());
            assertFalse(stage.getTitle().startsWith("•"), "a freshly opened file is not dirty");

            window.editor().area().appendText("Üçüncü satır");
            assertTrue(stage.getTitle().startsWith("•"));
            assertTrue(window.writeTo(file));
            assertFalse(stage.getTitle().startsWith("•"));
        });
        assertArrayEquals("Birinci satır\r\nİkinci satır\r\nÜçüncü satır".getBytes(CP1254), Files.readAllBytes(file));
    }

    @Test
    void savingAnEmojiIntoAWindows1254FileSwitchesToUtf8AndSaysSo() throws IOException {
        Path file = dir.resolve("ay.txt");
        Files.write(file, "Gece ığ".getBytes(CP1254));   // pure ASCII would be detected as UTF-8
        Fx.run(() -> {
            window.openPath(file);
            window.editor().area().appendText(" 🌙");
            assertTrue(window.writeTo(file));
            assertTrue(window.statusText().contains("as UTF-8"), window.statusText());
        });
        assertEquals("Gece ığ 🌙", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void commandLineArgumentOpensAnExistingFileOrStartsANewOne() throws IOException {
        Path existing = dir.resolve("var.md");
        Files.writeString(existing, "# Başlık");
        Fx.run(() -> {
            window.openFromCommandLine(existing.toString());
            assertEquals("# Başlık", text());
        });

        Path fresh = dir.resolve("yeni.txt");
        Fx.run(() -> {
            window.openFromCommandLine(fresh.toString());
            assertEquals("", text());
            assertTrue(stage.getTitle().contains("yeni.txt"), stage.getTitle());
            assertTrue(window.statusText().startsWith("New file: yeni.txt"));
            assertFalse(Files.exists(fresh), "nothing is created until the writer saves");
            window.editor().area().replaceText("ilk satır");
            assertTrue(window.writeTo(fresh));
        });
        assertEquals("ilk satır", Files.readString(fresh));

        Fx.run(() -> {
            window.openFromCommandLine(dir.resolve("no-such-folder").resolve("x.txt").toString());
            assertTrue(window.statusText().startsWith("Folder not found"), window.statusText());
            assertEquals("ilk satır", text(), "the current document is untouched");
        });
    }

    @Test
    void savingIsRefusedDuringAnAiPreviewAndTheFileIsUntouched() throws IOException {
        Path file = dir.resolve("p.txt");
        Files.writeString(file, "draft");
        Fx.run(() -> {
            window.openPath(file);
            window.editor().beginPreview(Span.caret(5));
            window.editor().appendGhost(" ghost");
            assertFalse(window.writeTo(file));
            window.editor().endPreview();
        });
        assertEquals("draft", Files.readString(file));
    }

    // --- find ------------------------------------------------------------------------------------

    @Test
    void ctrlFOpensTheFindBarAndTypingJumpsToTheFirstMatch() {
        Fx.run(() -> {
            window.editor().load("Kedi uyudu. Sonra kedi uyandı. KEDİ koştu.");
            assertFalse(window.find().isOpen());

            press(window.editor().area(), KeyCode.F, false, true);
            assertTrue(window.find().isOpen());

            window.find().queryField().setText("kedi");
            assertEquals("Kedi", selected());
            assertEquals("1 of 3", window.find().counterText());
        });
    }

    @Test
    void enterF3AndShiftVariantsWalkTheMatchesAndWrap() {
        Fx.run(() -> {
            window.editor().load("one two one two one");
            press(window.editor().area(), KeyCode.F, false, true);
            window.find().queryField().setText("one");
            assertEquals(new Span(0, 3), selection());

            press(window.find().queryField(), KeyCode.ENTER, false, false);
            assertEquals(new Span(8, 11), selection());
            press(window.editor().area(), KeyCode.F3, false, false);
            assertEquals(new Span(16, 19), selection());
            assertEquals("3 of 3", window.find().counterText());
            press(window.editor().area(), KeyCode.F3, false, false);
            assertEquals(new Span(0, 3), selection(), "wraps to the first");
            press(window.find().queryField(), KeyCode.ENTER, true, false);
            assertEquals(new Span(16, 19), selection(), "Shift+Enter wraps to the last");
            press(window.editor().area(), KeyCode.F3, true, false);
            assertEquals(new Span(8, 11), selection());
        });
    }

    @Test
    void matchCaseAndNoMatchFeedback() {
        Fx.run(() -> {
            window.editor().load("Ada ada ADA");
            press(window.editor().area(), KeyCode.F, false, true);
            window.find().queryField().setText("ada");
            assertEquals("1 of 3", window.find().counterText());

            window.find().matchCaseBox().setSelected(true);
            assertEquals(new Span(4, 7), selection());
            assertEquals("1 of 1", window.find().counterText());

            window.find().queryField().setText("zeynep");
            assertEquals("No matches", window.find().counterText());
            assertTrue(window.find().queryField().getStyleClass().contains(FindBar.NO_MATCH));
            window.find().queryField().setText("ADA");
            assertFalse(window.find().queryField().getStyleClass().contains(FindBar.NO_MATCH));
        });
    }

    @Test
    void escClosesTheFindBarAndKeepsTheMatchSelected() {
        Fx.run(() -> {
            window.editor().load("alpha beta gamma");
            press(window.editor().area(), KeyCode.F, false, true);
            window.find().queryField().setText("beta");
            press(window.find().queryField(), KeyCode.ESCAPE, false, false);
            assertFalse(window.find().isOpen());
            assertEquals("beta", selected());
        });
    }

    @Test
    void shortSelectionPrefillsTheQueryAndEditsUpdateTheCount() {
        Fx.run(() -> {
            window.editor().load("sis ve sis");
            window.editor().area().selectRange(7, 10);
            press(window.editor().area(), KeyCode.F, false, true);
            assertEquals("sis", window.find().queryField().getText());
            assertEquals(new Span(7, 10), selection(), "the selected occurrence stays selected");
            assertEquals("2 of 2", window.find().counterText());

            window.editor().area().appendText(" sis");
            assertEquals("2 of 3", window.find().counterText(), "count follows edits while the bar is open");
        });
    }

    @Test
    void f3WithNothingToFindOpensTheBarInsteadOfDoingNothing() {
        Fx.run(() -> {
            window.editor().load("text");
            press(window.editor().area(), KeyCode.F3, false, false);
            assertTrue(window.find().isOpen());
        });
    }

    private Span selection() {
        var s = window.editor().area().getSelection();
        return new Span(s.getStart(), s.getEnd());
    }

    // --- status bar ------------------------------------------------------------------------------

    @Test
    void wordCountFollowsTheDocumentAndTheSelectionButNotTheAiPreview() {
        Fx.run(() -> {
            window.editor().load("Bir iki üç dört beş.");
            assertEquals("5 words · 20 characters · 1 min read", window.statsText());

            window.editor().area().selectRange(0, 7);
            assertEquals("2 of 5 words selected · 7 characters", window.statsText());

            window.editor().area().moveTo(20);
            String before = window.statsText();
            window.editor().beginPreview(Span.caret(20));
            window.editor().appendGhost(" Altı yedi sekiz.");
            assertEquals(before, window.statsText(), "ghost text is not part of the document yet");
            window.editor().endPreview();
            assertEquals("5 words · 20 characters · 1 min read", window.statsText());
        });
    }

    // --- instruction history ---------------------------------------------------------------------

    @Test
    void upAndDownInTheInstructionBarRecallEarlierInstructions() {
        Fx.run(() -> {
            window.history().record("daha kısa");
            window.history().record("more formal");
            var field = window.commandField();
            field.setText("yarım");

            press(field, KeyCode.UP, false, false);
            assertEquals("more formal", field.getText());
            assertEquals(field.getLength(), field.getCaretPosition(), "caret goes to the end");
            press(field, KeyCode.UP, false, false);
            assertEquals("daha kısa", field.getText());
            press(field, KeyCode.DOWN, false, false);
            press(field, KeyCode.DOWN, false, false);
            assertEquals("yarım", field.getText(), "the half-typed line comes back");
        });
    }
}
