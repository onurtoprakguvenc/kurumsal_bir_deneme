package org.yazi.desktop;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.yazi.text.ContextWindowPolicy;

/**
 * Right-hand panel: the latest answer (read-only, replaced by the next question, never sent back to the model)
 * and the writer's pinned notes for this document.
 */
final class SidePanel {

    private final VBox root = new VBox(8);
    private final Label answerTitle = new Label("Answer");
    private final TextArea answer = new TextArea();
    private final TextArea notes = new TextArea();
    private final Label notesCount = new Label();

    SidePanel() {
        root.getStyleClass().add("side-panel");
        root.setPadding(new Insets(10));
        root.setPrefWidth(320);
        root.setMinWidth(220);

        answerTitle.getStyleClass().add("panel-title");
        answer.setEditable(false);
        answer.setWrapText(true);
        answer.setPromptText("Answers to Ask appear here.");
        VBox.setVgrow(answer, Priority.ALWAYS);

        Button copy = new Button("Copy");
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(answer.getText());
            Clipboard.getSystemClipboard().setContent(content);
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox answerHeader = new HBox(6, answerTitle, spacer, copy);

        Label notesTitle = new Label("Pinned notes");
        notesTitle.getStyleClass().add("panel-title");
        notes.setWrapText(true);
        notes.setPrefRowCount(6);
        notes.setPromptText("Optional background sent with every request: terms, audience, tone. Keep it short.");
        notes.setTextFormatter(new javafx.scene.control.TextFormatter<String>(change ->
                change.getControlNewText().length() <= ContextWindowPolicy.BRIEF_MAX_CHARS ? change : null));
        notesCount.getStyleClass().add("muted");
        notes.textProperty().addListener((obs, old, text) -> updateCount());
        updateCount();

        root.getChildren().addAll(answerHeader, answer, notesTitle, notes, notesCount);
    }

    VBox view() {
        return root;
    }

    String notes() {
        return notes.getText();
    }

    void answerStarted(String question) {
        answerTitle.setText(question == null || question.isBlank() ? "Answer" : "Answer: " + abbreviate(question));
        answer.clear();
    }

    void answerAppend(String fragment) {
        answer.appendText(fragment);
    }

    void answerFinished(String text, boolean truncated) {
        answer.setText(truncated ? text + "\n\n[cut off at the length limit]" : text);
    }

    private void updateCount() {
        notesCount.setText(notes.getLength() + " / " + ContextWindowPolicy.BRIEF_MAX_CHARS);
    }

    private static String abbreviate(String s) {
        String flat = s.strip().replaceAll("\\s+", " ");
        return flat.length() <= 40 ? flat : flat.substring(0, 40) + "…";
    }
}
