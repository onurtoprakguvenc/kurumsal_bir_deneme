package org.yazi.desktop;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.yazi.text.ContextWindowPolicy;

/**
 * Right-hand panel, two cards:
 *
 * <ul>
 *   <li><b>Answer</b>: the latest answer or tool result (read-only, replaced by the next one, never sent back to
 *       the model). A heading shows what was asked; Copy (with a brief "Copied" confirmation) and Clear sit in the
 *       card header; a footer counts words. Tool results use a fixed-width font.</li>
 *   <li><b>Pinned notes</b>: short background sent with every request, with a live counter that turns amber near
 *       the limit.</li>
 * </ul>
 */
final class SidePanel {

    static final String MONOSPACE = "monospace";
    static final String WARNING = "warning";
    /** The notes counter turns amber from this share of the limit. */
    static final double NOTES_WARNING_SHARE = 0.9;

    private final VBox root = new VBox(10);
    private final Label heading = new Label();
    private final TextArea answer = new TextArea();
    private final Label answerMeta = new Label();
    private final Button copy = new Button("Copy");
    private final Button clear = new Button("Clear");
    private final TextArea notes = new TextArea();
    private final Label notesCount = new Label();
    private final PauseTransition copiedReset = new PauseTransition(Duration.seconds(1.4));

    SidePanel() {
        root.getStyleClass().add("side-panel");
        root.setPadding(new Insets(12));
        root.setPrefWidth(340);
        root.setMinWidth(240);

        // --- answer card ---------------------------------------------------------------------------
        copy.getStyleClass().add("flat");
        copy.setTooltip(new Tooltip("Copy the answer"));
        copy.setOnAction(e -> copyAnswer());
        copy.disableProperty().bind(answer.textProperty().isEmpty());
        copiedReset.setOnFinished(e -> copy.setText("Copy"));
        clear.getStyleClass().add("flat");
        clear.setTooltip(new Tooltip("Clear the answer"));
        clear.setOnAction(e -> clearAnswer());
        clear.disableProperty().bind(answer.textProperty().isEmpty());

        heading.getStyleClass().add("panel-heading");
        heading.setWrapText(true);
        heading.setMaxHeight(60);
        heading.managedProperty().bind(heading.textProperty().isNotEmpty());
        heading.visibleProperty().bind(heading.managedProperty());

        answer.setEditable(false);
        answer.setWrapText(true);
        answer.setPromptText("Answers to Ask and Compile results appear here.");
        answer.getStyleClass().add("answer-area");
        answer.setId("answer");
        VBox.setVgrow(answer, Priority.ALWAYS);
        answerMeta.getStyleClass().add("muted");
        answer.textProperty().addListener((obs, old, text) -> updateAnswerMeta());

        VBox answerCard = card(header("Answer", copy, clear), heading, answer, answerMeta);
        VBox.setVgrow(answerCard, Priority.ALWAYS);

        // --- notes card ----------------------------------------------------------------------------
        notes.setWrapText(true);
        notes.setPrefRowCount(6);
        notes.setId("notes");
        notes.setPromptText("Optional background sent with every request: terms, audience, tone. Keep it short.");
        notes.setTextFormatter(new TextFormatter<String>(change ->
                change.getControlNewText().length() <= ContextWindowPolicy.BRIEF_MAX_CHARS ? change : null));
        notesCount.getStyleClass().add("muted");
        notes.textProperty().addListener((obs, old, text) -> updateCount());
        updateCount();

        VBox notesCard = card(header("Pinned notes", notesCount), notes);
        root.getChildren().addAll(answerCard, notesCard);
    }

    VBox view() {
        return root;
    }

    String notes() {
        return notes.getText();
    }

    // For focus navigation and tests.
    TextArea answerArea() {
        return answer;
    }

    TextArea notesArea() {
        return notes;
    }

    Button copyButton() {
        return copy;
    }

    Button clearButton() {
        return clear;
    }

    String heading() {
        return heading.getText();
    }

    String answerMeta() {
        return answerMeta.getText();
    }

    String notesCounter() {
        return notesCount.getText();
    }

    boolean notesCounterWarns() {
        return notesCount.getStyleClass().contains(WARNING);
    }

    // ------------------------------------------------------------------------------------------------

    void answerStarted(String question) {
        heading.setText(question == null || question.isBlank() ? "" : abbreviate(question));
        answer.getStyleClass().remove(MONOSPACE);
        answer.clear();
    }

    void answerAppend(String fragment) {
        answer.appendText(fragment);
    }

    void answerFinished(String text, boolean truncated) {
        answer.setText(truncated ? text + "\n\n[cut off at the length limit]" : text);
    }

    /** A tool result: titled, fixed-width, scrolled to the top. */
    void showOutput(String title, String text) {
        answerStarted(title);
        answer.getStyleClass().add(MONOSPACE);
        answer.setText(text);
        answer.positionCaret(0);
        answer.setScrollTop(0);
    }

    boolean isMonospace() {
        return answer.getStyleClass().contains(MONOSPACE);
    }

    void copyAnswer() {
        ClipboardContent content = new ClipboardContent();
        content.putString(answer.getText());
        Clipboard.getSystemClipboard().setContent(content);
        copy.setText("Copied ✓");
        copiedReset.playFromStart();
    }

    void clearAnswer() {
        heading.setText("");
        answer.getStyleClass().remove(MONOSPACE);
        answer.clear();
    }

    private void updateAnswerMeta() {
        String text = answer.getText();
        if (text.isEmpty()) {
            answerMeta.setText("");
            return;
        }
        int words = DocumentStats.of(text).words();
        answerMeta.setText(String.format("%,d %s", words, words == 1 ? "word" : "words"));
    }

    private void updateCount() {
        int length = notes.getLength();
        int max = ContextWindowPolicy.BRIEF_MAX_CHARS;
        notesCount.setText(String.format("%,d / %,d", length, max));
        notesCount.getStyleClass().remove(WARNING);
        if (length >= max * NOTES_WARNING_SHARE) {
            notesCount.getStyleClass().add(WARNING);
        }
    }

    private static HBox header(String title, javafx.scene.Node... trailing) {
        Label label = new Label(title.toUpperCase(java.util.Locale.ROOT));
        label.getStyleClass().add("section-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox box = new HBox(4, label, spacer);
        box.getChildren().addAll(trailing);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static VBox card(javafx.scene.Node... children) {
        VBox card = new VBox(6, children);
        card.getStyleClass().add("card");
        return card;
    }

    private static String abbreviate(String s) {
        String flat = s.strip().replaceAll("\\s+", " ");
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "…";
    }
}
