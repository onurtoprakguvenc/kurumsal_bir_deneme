package org.yazi.desktop;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.yazi.gateway.Usage;
import org.yazi.model.Tier;
import org.yazi.prose.ModelCatalog;
import org.yazi.prose.ProsePipeline;
import org.yazi.prose.ProseRouter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The single editor window: menu, editor, command bar, side panel, status bar.
 *
 * <pre>
 *   Ctrl+Enter          write at the caret / rewrite the selection (uses the command bar text, if any)
 *   Ctrl+K              focus the command bar;  Enter = apply, Shift+Enter = ask
 *   Ctrl+Shift+Enter    ask about the selection or the text around the caret
 *   Tab / Esc           accept / discard a rewrite;  Esc also cancels a running request
 *   Ctrl+N/O/S          new / open / save;  Ctrl+Shift+S save as
 * </pre>
 */
final class MainWindow implements OperationController.View {

    private final Stage stage;
    private final KeyedGateway gateway;
    private final EditorPane editor = new EditorPane();
    private final SidePanel side = new SidePanel();
    private final OperationController operations;
    private final CompileActions compile;

    private final TextField command = new TextField();
    private final ComboBox<Tier> tier = new ComboBox<>();
    private final CheckBox wholeDocument = new CheckBox("Whole document");
    private final Label status = new Label("Ready.");
    private final Label cost = new Label();

    private Path file;
    private long savedRevision;
    private long sessionTokens;

    MainWindow(Stage stage, KeyedGateway gateway, ModelCatalog models) {
        this.stage = stage;
        this.gateway = gateway;
        this.operations = new OperationController(editor, new ProsePipeline(gateway, models), this, side::notes);
        this.compile = new CompileActions(stage, editor, gateway, this, this::askForKey);
    }

    Scene buildScene() {
        BorderPane root = new BorderPane();
        root.setTop(menuBar());

        SplitPane split = new SplitPane(editor.view(), side.view());
        split.setDividerPositions(0.72);
        SplitPane.setResizableWithParent(side.view(), false);
        root.setCenter(split);
        root.setBottom(new VBox(commandBar(), statusBar()));

        Scene scene = new Scene(root, 1100, 720);
        scene.getStylesheets().add(MainWindow.class.getResource("yazi.css").toExternalForm());
        installShortcuts(scene);

        editor.revisionProperty().addListener((obs, old, rev) -> updateTitle());
        operations.stateProperty().addListener((obs, old, s) -> {
            boolean idle = s == OperationController.State.IDLE;
            command.setDisable(!idle);
            tier.setDisable(!idle);
        });
        updateTitle();
        stage.setOnCloseRequest(e -> {
            if (!confirmDiscard()) {
                e.consume();
            }
        });
        return scene;
    }

    void focusEditor() {
        editor.area().requestFocus();
    }

    /** For tests that need to put the editor into a specific state. */
    EditorPane editor() {
        return editor;
    }

    // ------------------------------------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------------------------------------

    private MenuBar menuBar() {
        MenuItem newFile = item("New", new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN), this::newFile);
        MenuItem open = item("Open…", new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN), this::open);
        MenuItem save = item("Save", new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN), this::save);
        MenuItem saveAs = item("Save As…",
                new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN), this::saveAs);
        MenuItem key = item("API key…", null, this::askForKey);
        MenuItem exit = item("Exit", null, () -> {
            if (confirmDiscard()) {
                stage.close();
            }
        });
        Menu fileMenu = new Menu("File", null, newFile, open, save, saveAs, new SeparatorMenuItem(), key,
                new SeparatorMenuItem(), exit);

        MenuItem write = item("Write / rewrite", new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN),
                () -> run(ProseRouter.Gesture.APPLY));
        MenuItem ask = item("Ask",
                new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                () -> run(ProseRouter.Gesture.ASK));
        MenuItem focusCommand = item("Instruction bar", new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN),
                command::requestFocus);
        Menu aiMenu = new Menu("Assist", null, write, ask, focusCommand);

        MenuItem video = item("Video prompt from selection…", null, compile::compileVideo);
        MenuItem image = item("Image prompt from selection…", null, compile::compileImage);
        MenuItem metaPrompt = item("Meta-prompt from selection…", null, compile::compileMetaPrompt);
        Menu compileMenu = new Menu("Compile", null, video, image, metaPrompt);
        return new MenuBar(fileMenu, aiMenu, compileMenu);
    }

    private HBox commandBar() {
        command.setPromptText("Instruction (optional)   Enter: write / rewrite selection   Shift+Enter: ask   Esc: back to text");
        HBox.setHgrow(command, Priority.ALWAYS);
        command.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && !e.isShortcutDown()) {
                run(e.isShiftDown() ? ProseRouter.Gesture.ASK : ProseRouter.Gesture.APPLY);
                e.consume();
            }
        });

        tier.getItems().setAll(Tier.values());
        tier.valueProperty().bindBidirectional(operations.tierProperty());
        tier.setTooltip(new javafx.scene.control.Tooltip(
                "FAST: smallest context\nBALANCED: default\nDEEP: larger context, stronger model"));

        Button write = new Button("Write");
        write.setOnAction(e -> run(ProseRouter.Gesture.APPLY));
        Button ask = new Button("Ask");
        ask.setOnAction(e -> run(ProseRouter.Gesture.ASK));
        write.disableProperty().bind(command.disabledProperty());
        ask.disableProperty().bind(command.disabledProperty());
        wholeDocument.setTooltip(new javafx.scene.control.Tooltip("Ask about the whole document (capped) instead of the selection or nearby text."));

        HBox bar = new HBox(8, command, tier, write, ask, wholeDocument);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 10, 6, 10));
        bar.getStyleClass().add("command-bar");
        return bar;
    }

    private HBox statusBar() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        cost.getStyleClass().add("muted");
        HBox bar = new HBox(8, status, spacer, cost);
        bar.setPadding(new Insets(3, 10, 4, 10));
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    private void installShortcuts(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                if (operations.state() != OperationController.State.IDLE) {
                    operations.cancelOrReject();
                } else if (command.isFocused()) {
                    focusEditor();
                }
                e.consume();
            } else if (e.getCode() == KeyCode.TAB && operations.state() == OperationController.State.REVIEW) {
                operations.accept();
                focusEditor();
                e.consume();
            }
        });
    }

    // ------------------------------------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------------------------------------

    private void run(ProseRouter.Gesture gesture) {
        if (operations.state() != OperationController.State.IDLE) {
            return;
        }
        if (!gateway.hasKey() && !askForKey()) {
            status("No API key: the editor works, the assistant needs a key (File → API key…).");
            return;
        }
        ProseRouter.Gesture effective = (gesture == ProseRouter.Gesture.ASK && wholeDocument.isSelected())
                ? ProseRouter.Gesture.ASK_DOCUMENT : gesture;
        var snapshot = editor.snapshot();
        if (effective == ProseRouter.Gesture.APPLY && snapshot.text().isBlank() && command.getText().isBlank()) {
            status("Empty document: type something first, or write an instruction.");
            return;
        }
        operations.start(ProseRouter.route(snapshot, command.getText(), effective));
        focusEditor();
    }

    private boolean askForKey() {
        Dialog<String> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("Gemini API key");
        dialog.setHeaderText("Paste your Gemini API key.\nIt stays in memory for this session only.\n"
                + "Tip: set the GEMINI_API_KEY environment variable to skip this.");
        PasswordField field = new PasswordField();
        field.setPrefColumnCount(36);
        dialog.getDialogPane().setContent(field);
        ButtonType ok = new ButtonType("Use key", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
        dialog.setResultConverter(b -> b == ok ? field.getText() : null);
        dialog.setOnShown(e -> field.requestFocus());
        Optional<String> key = dialog.showAndWait().map(String::strip).filter(s -> !s.isEmpty());
        key.ifPresent(k -> {
            gateway.setKey(k);
            status("API key set for this session.");
        });
        return key.isPresent();
    }

    private void newFile() {
        if (!confirmDiscard()) {
            return;
        }
        editor.load("");
        file = null;
        savedRevision = editor.revision();
        updateTitle();
    }

    private void open() {
        if (!confirmDiscard()) {
            return;
        }
        File chosen = chooser().showOpenDialog(stage);
        if (chosen == null) {
            return;
        }
        try {
            editor.load(Files.readString(chosen.toPath(), StandardCharsets.UTF_8));
            file = chosen.toPath();
            savedRevision = editor.revision();
            updateTitle();
            status("Opened " + chosen.getName());
        } catch (IOException e) {
            error("Could not open " + chosen.getName(), e);
        }
    }

    private boolean save() {
        return (file == null) ? saveAs() : writeTo(file);
    }

    private boolean saveAs() {
        FileChooser chooser = chooser();
        if (file != null) {
            chooser.setInitialDirectory(file.getParent().toFile());
            chooser.setInitialFileName(file.getFileName().toString());
        }
        File chosen = chooser.showSaveDialog(stage);
        return chosen != null && writeTo(chosen.toPath());
    }

    private boolean writeTo(Path target) {
        if (editor.isPreviewing()) {
            status("Finish or discard the current AI edit before saving.");
            return false;
        }
        try {
            Files.writeString(target, editor.area().getText(), StandardCharsets.UTF_8);
            file = target;
            savedRevision = editor.revision();
            updateTitle();
            status("Saved " + target.getFileName());
            return true;
        } catch (IOException e) {
            error("Could not save " + target.getFileName(), e);
            return false;
        }
    }

    private boolean confirmDiscard() {
        if (!isDirty()) {
            return true;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "Save changes to " + displayName() + "?",
                ButtonType.YES, ButtonType.NO, ButtonType.CANCEL);
        alert.initOwner(stage);
        alert.setHeaderText(null);
        ButtonType answer = alert.showAndWait().orElse(ButtonType.CANCEL);
        if (answer == ButtonType.YES) {
            return save();
        }
        return answer == ButtonType.NO;
    }

    private FileChooser chooser() {
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Text", "*.txt", "*.md"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        return chooser;
    }

    private boolean isDirty() {
        return editor.revision() != savedRevision;
    }

    private String displayName() {
        return (file == null) ? "Untitled" : file.getFileName().toString();
    }

    private void updateTitle() {
        stage.setTitle((isDirty() ? "• " : "") + displayName() + " — Yazı");
    }

    private void error(String message, Exception e) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message + "\n" + e.getMessage(), ButtonType.OK);
        alert.initOwner(stage);
        alert.showAndWait();
    }

    private static MenuItem item(String text, KeyCombination accelerator, Runnable action) {
        MenuItem item = new MenuItem(text);
        if (accelerator != null) {
            item.setAccelerator(accelerator);
        }
        item.setOnAction(e -> action.run());
        return item;
    }

    // ------------------------------------------------------------------------------------------------
    // OperationController.View
    // ------------------------------------------------------------------------------------------------

    @Override
    public void answerStarted(String question) {
        side.answerStarted(question);
    }

    @Override
    public void answerAppend(String fragment) {
        side.answerAppend(fragment);
    }

    @Override
    public void answerFinished(String text, boolean truncated) {
        side.answerFinished(text, truncated);
    }

    @Override
    public void status(String message) {
        status.setText(message);
    }

    @Override
    public void cost(Usage usage, int windowChars) {
        sessionTokens += usage.totalTokens();
        cost.setText(String.format("last: %,d in / %,d out  ·  context %,d chars  ·  session %,d tokens",
                usage.promptTokens(), usage.outputTokens(), windowChars, sessionTokens));
    }
}
