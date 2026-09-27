package org.yazi.desktop;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.yazi.gateway.GatewayException;
import org.yazi.gateway.ResilientGateway;
import org.yazi.gateway.Usage;
import org.yazi.model.Tier;
import org.yazi.prose.ModelCatalog;
import org.yazi.prose.ProsePipeline;
import org.yazi.prose.ProseRouter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The single editor window: menus, editor, find bar, instruction bar, side panel, status bar. Every shortcut is
 * declared in {@link Keys}; F1 shows them all.
 *
 * <p>Layout, top to bottom: File / Edit / View / Assist / Compile / Help menus; the editor (a centred writing
 * column, see {@link EditorLayout}) beside the side panel; the find bar (hidden until Ctrl+F); the instruction
 * bar (instruction, tier, Write, Ask); the status bar (activity dot, message, file format, word count, session
 * usage).</p>
 *
 * <p>Appearance (theme, editor font and size, side panel) is kept in {@link UiSettings}; documents and model
 * requests never are. Files keep their encoding, byte-order mark and line endings across open and save (see
 * {@link TextFiles}).</p>
 */
final class MainWindow implements OperationController.View {

    private final Stage stage;
    private final KeyedGateway gateway;
    private final UiSettings settings;
    private final EditorPane editor = new EditorPane();
    private final SidePanel side = new SidePanel();
    private final OperationController operations;
    private final CompileActions compile;
    private final FindBar find = new FindBar(editor.area());
    private final InstructionHistory history = new InstructionHistory();
    private final UsageMeter meter;

    private final TextField command = new TextField();
    private final Segmented<Tier> tier = new Segmented<>(List.of(Tier.values()),
            t -> t.name().charAt(0) + t.name().substring(1).toLowerCase(Locale.ROOT), Tier.BALANCED);
    private final CheckBox wholeDocument = new CheckBox("Whole document");
    private final Region statusDot = new Region();
    private final Tooltip statusDotTip = new Tooltip();
    private final FadeTransition pulse = new FadeTransition(Duration.millis(700), statusDot);
    private final Label status = new Label("Ready.");
    private final Label formatLabel = new Label();
    private final Label stats = new Label();
    private final Label usage = new Label();
    private final Tooltip usageTip = new Tooltip();
    private final Map<Tier, RadioMenuItem> tierItems = new EnumMap<>(Tier.class);
    private final CheckMenuItem darkTheme = new CheckMenuItem("Dark theme");
    private final CheckMenuItem sidePanelItem = new CheckMenuItem("Side panel");
    private final CheckMenuItem wholeDocumentItem = new CheckMenuItem("Ask about the whole document");
    private final Map<UiSettings.EditorFont, RadioMenuItem> fontItems = new EnumMap<>(UiSettings.EditorFont.class);

    private BorderPane root;
    private SplitPane split;
    private FocusCycle focusCycle;
    private double dividerPosition = 0.72;
    private OperationController.Activity activity = OperationController.Activity.IDLE;

    private Path file;
    private TextFiles.Format format = TextFiles.Format.DEFAULT;
    private long savedRevision;
    private DocumentStats documentStats = DocumentStats.of("");

    MainWindow(Stage stage, KeyedGateway gateway, ModelCatalog models) {
        this(stage, gateway, models, UiSettings.inMemory(), Optional.empty());
    }

    MainWindow(Stage stage, KeyedGateway gateway, ModelCatalog models, UiSettings settings,
               Optional<UsageMeter.Rates> rates) {
        this.stage = stage;
        this.gateway = gateway;
        this.settings = settings;
        this.meter = new UsageMeter(rates);
        this.operations = new OperationController(editor, new ProsePipeline(gateway, models), this, side::notes);
        this.compile = new CompileActions(stage, editor, gateway, this, this::askForKey, this::theme);
        gateway.setListener(new ResilientGateway.Listener() {
            @Override
            public void retrying(int attempt, int maxAttempts, java.time.Duration wait, GatewayException cause) {
                String message = retryMessage(attempt, maxAttempts, wait, cause);
                Platform.runLater(() -> status(message));
            }

            @Override
            public void modelFallback(String requested, String fallback) {
                String message = fallbackMessage(requested, fallback);
                Platform.runLater(() -> status(message));
            }
        });
    }

    Scene buildScene() {
        root = new BorderPane();
        root.setTop(menuBar());

        split = new SplitPane(editor.view(), side.view());
        split.setDividerPositions(dividerPosition);
        SplitPane.setResizableWithParent(side.view(), false);
        root.setCenter(split);
        root.setBottom(new VBox(find.view(), commandBar(), statusBar()));

        Scene scene = new Scene(root, 1180, 760);
        Styles.theme(root, settings.theme());
        installShortcuts(scene);
        installEditorLayout();
        focusCycle = new FocusCycle(List.of(
                new FocusCycle.Region("editor", this::focusEditor, () -> true, () -> owns(editor.area())),
                new FocusCycle.Region("find", () -> find.queryField().requestFocus(),
                        () -> find.isOpen() && !find.view().isDisabled(), () -> owns(find.queryField())),
                new FocusCycle.Region("instruction", command::requestFocus, () -> !command.isDisabled(),
                        () -> owns(command)),
                new FocusCycle.Region("answer", () -> side.answerArea().requestFocus(), this::isSidePanelVisible,
                        () -> owns(side.answerArea())),
                new FocusCycle.Region("notes", () -> side.notesArea().requestFocus(), this::isSidePanelVisible,
                        () -> owns(side.notesArea()))));

        editor.revisionProperty().addListener((obs, old, rev) -> {
            updateTitle();
            documentStats = DocumentStats.of(editor.area().getText());
            updateStats();
            if (find.isOpen()) {
                find.refresh();
            }
        });
        editor.area().selectionProperty().addListener((obs, old, sel) -> updateStats());
        operations.stateProperty().addListener((obs, old, s) -> {
            boolean idle = s == OperationController.State.IDLE;
            command.setDisable(!idle);
            tier.view().setDisable(!idle);
            find.setDisable(!idle);
        });
        operations.tierProperty().addListener((obs, old, t) -> {
            RadioMenuItem item = tierItems.get(t);
            if (item != null) {
                item.setSelected(true);
            }
        });

        applyTheme(settings.theme());
        applyEditorFont();
        setSidePanelVisible(settings.sidePanelVisible());
        updateTitle();
        updateStats();
        updateFormat();
        updateUsage();
        activity(OperationController.Activity.IDLE);
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

    // ------------------------------------------------------------------------------------------------
    // Test hooks
    // ------------------------------------------------------------------------------------------------

    EditorPane editor() {
        return editor;
    }

    FindBar find() {
        return find;
    }

    SidePanel side() {
        return side;
    }

    String statsText() {
        return stats.getText();
    }

    String statusText() {
        return status.getText();
    }

    String formatText() {
        return formatLabel.getText();
    }

    String usageText() {
        return usage.getText();
    }

    String usageDetails() {
        return usageTip.getText();
    }

    TextField commandField() {
        return command;
    }

    Segmented<Tier> tierControl() {
        return tier;
    }

    OperationController operations() {
        return operations;
    }

    InstructionHistory history() {
        return history;
    }

    UiSettings.Theme theme() {
        return settings.theme();
    }

    double fontSize() {
        return settings.fontSize();
    }

    List<String> statusDotClasses() {
        return List.copyOf(statusDot.getStyleClass());
    }

    Node root() {
        return root;
    }

    /** The first menu item with this text, searched through all menus and submenus. */
    MenuItem menuItem(String text) {
        for (Menu menu : ((MenuBar) root.getTop()).getMenus()) {
            MenuItem found = findItem(menu, text);
            if (found != null) {
                return found;
            }
        }
        throw new IllegalArgumentException("No menu item " + text);
    }

    private static MenuItem findItem(Menu menu, String text) {
        for (MenuItem item : menu.getItems()) {
            if (text.equals(item.getText())) {
                return item;
            }
            if (item instanceof Menu sub) {
                MenuItem found = findItem(sub, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------
    // Menus
    // ------------------------------------------------------------------------------------------------

    private MenuBar menuBar() {
        Menu fileMenu = new Menu("File", null,
                item("New", Keys.NEW, this::newFile),
                item("Open…", Keys.OPEN, this::open),
                item("Save", Keys.SAVE, this::save),
                item("Save As…", Keys.SAVE_AS, this::saveAs),
                new SeparatorMenuItem(),
                item("API key…", null, this::askForKey),
                new SeparatorMenuItem(),
                item("Exit", null, () -> {
                    if (confirmDiscard()) {
                        stage.close();
                    }
                }));

        Menu editMenu = new Menu("Edit", null,
                item("Undo", Keys.UNDO, () -> editAction(TextInputControl::undo, a -> a.undo())),
                item("Redo", Keys.REDO, () -> editAction(TextInputControl::redo, a -> a.redo())),
                new SeparatorMenuItem(),
                item("Cut", Keys.CUT, () -> editAction(TextInputControl::cut, a -> a.cut())),
                item("Copy", Keys.COPY, () -> editAction(TextInputControl::copy, a -> a.copy())),
                item("Paste", Keys.PASTE, () -> editAction(TextInputControl::paste, a -> a.paste())),
                item("Select All", Keys.SELECT_ALL, () -> editAction(TextInputControl::selectAll, a -> a.selectAll())),
                new SeparatorMenuItem(),
                item("Find…", Keys.FIND, this::openFind),
                item("Find Next", Keys.FIND_NEXT, this::findNext),
                item("Find Previous", Keys.FIND_PREVIOUS, this::findPrevious));

        darkTheme.setAccelerator(Keys.TOGGLE_THEME);
        darkTheme.setOnAction(e -> applyTheme(darkTheme.isSelected() ? UiSettings.Theme.DARK : UiSettings.Theme.LIGHT));
        sidePanelItem.setAccelerator(Keys.TOGGLE_SIDE_PANEL);
        sidePanelItem.setOnAction(e -> setSidePanelVisible(sidePanelItem.isSelected()));
        ToggleGroup fonts = new ToggleGroup();
        Menu fontMenu = new Menu("Editor font");
        for (UiSettings.EditorFont f : UiSettings.EditorFont.values()) {
            RadioMenuItem r = new RadioMenuItem(CompileActions.titleCase(f.name()));
            r.setToggleGroup(fonts);
            r.setOnAction(e -> {
                settings.editorFont(f);
                applyEditorFont();
            });
            fontItems.put(f, r);
            fontMenu.getItems().add(r);
        }
        Menu goTo = new Menu("Go to", null,
                item("Next region", Keys.FOCUS_NEXT, () -> focusCycle.move(false)),
                item("Previous region", Keys.FOCUS_PREVIOUS, () -> focusCycle.move(true)),
                new SeparatorMenuItem(),
                item("Editor", Keys.FOCUS_EDITOR, this::focusEditor),
                item("Instruction bar", Keys.FOCUS_INSTRUCTION, this::focusCommand),
                item("Answer", Keys.FOCUS_ANSWER, () -> focusSide(side.answerArea())),
                item("Pinned notes", Keys.FOCUS_NOTES, () -> focusSide(side.notesArea())));
        Menu viewMenu = new Menu("View", null,
                darkTheme, sidePanelItem, fontMenu,
                new SeparatorMenuItem(),
                item("Zoom In", Keys.ZOOM_IN, () -> zoom(1)),
                item("Zoom Out", Keys.ZOOM_OUT, () -> zoom(-1)),
                item("Actual Size", Keys.ZOOM_RESET, () -> setFontSize(UiSettings.DEFAULT_FONT)),
                new SeparatorMenuItem(),
                goTo,
                new SeparatorMenuItem(),
                item("Reset usage counter", null, this::resetUsage));

        ToggleGroup tiers = new ToggleGroup();
        Menu tierMenu = new Menu("Tier");
        Map<Tier, KeyCombination> tierKeys = Map.of(Tier.FAST, Keys.TIER_FAST, Tier.BALANCED, Keys.TIER_BALANCED,
                Tier.DEEP, Keys.TIER_DEEP);
        for (Tier t : Tier.values()) {
            RadioMenuItem r = new RadioMenuItem(tier.button(t).getText());
            r.setToggleGroup(tiers);
            r.setAccelerator(tierKeys.get(t));
            r.setOnAction(e -> {
                if (operations.state() == OperationController.State.IDLE) {
                    operations.tierProperty().set(t);
                } else {
                    tierItems.get(operations.tierProperty().get()).setSelected(true);
                }
            });
            r.setSelected(t == operations.tierProperty().get());
            tierItems.put(t, r);
            tierMenu.getItems().add(r);
        }
        wholeDocumentItem.selectedProperty().bindBidirectional(wholeDocument.selectedProperty());
        Menu aiMenu = new Menu("Assist", null,
                item("Write / rewrite", Keys.WRITE, () -> run(ProseRouter.Gesture.APPLY)),
                item("Ask", Keys.ASK, () -> run(ProseRouter.Gesture.ASK)),
                item("Instruction bar", Keys.INSTRUCTION_BAR, this::focusCommand),
                new SeparatorMenuItem(),
                tierMenu, wholeDocumentItem);

        Menu compileMenu = new Menu("Compile", null,
                item("Video prompt from selection…", null, compile::compileVideo),
                item("Image prompt from selection…", null, compile::compileImage),
                item("Meta-prompt from selection…", null, compile::compileMetaPrompt));

        Menu helpMenu = new Menu("Help", null,
                item("Keyboard shortcuts", Keys.SHORTCUTS, () -> ShortcutSheet.build(stage, theme()).showAndWait()),
                item("About Yazı", null, this::about));
        return new MenuBar(fileMenu, editMenu, viewMenu, aiMenu, compileMenu, helpMenu);
    }

    /** Edit actions go to the focused text field when there is one, otherwise to the editor. */
    private void editAction(Consumer<TextInputControl> onField, Consumer<org.fxmisc.richtext.StyleClassedTextArea> onEditor) {
        if (stage.getScene() != null && stage.getScene().getFocusOwner() instanceof TextInputControl field) {
            onField.accept(field);
        } else if (!editor.isPreviewing()) {
            onEditor.accept(editor.area());
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Bars
    // ------------------------------------------------------------------------------------------------

    private HBox commandBar() {
        command.setPromptText("Instruction (optional)  ·  Enter: write / rewrite  ·  Shift+Enter: ask  ·  ↑↓: history");
        command.getStyleClass().add("instruction-field");
        HBox.setHgrow(command, Priority.ALWAYS);
        command.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && !e.isShortcutDown()) {
                run(e.isShiftDown() ? ProseRouter.Gesture.ASK : ProseRouter.Gesture.APPLY);
                e.consume();
            } else if (e.getCode() == KeyCode.UP || e.getCode() == KeyCode.DOWN) {
                command.setText(e.getCode() == KeyCode.UP
                        ? history.previous(command.getText()) : history.next(command.getText()));
                command.end();
                e.consume();
            }
        });

        tier.valueProperty().bindBidirectional(operations.tierProperty());
        tier.id("tier")
                .tooltip(Tier.FAST, "Smallest context, fastest model  (" + Keys.TIER_FAST.getDisplayText() + ")")
                .tooltip(Tier.BALANCED, "Default  (" + Keys.TIER_BALANCED.getDisplayText() + ")")
                .tooltip(Tier.DEEP, "Larger context, stronger model  (" + Keys.TIER_DEEP.getDisplayText() + ")");

        Button write = new Button("Write");
        write.getStyleClass().add("primary");
        write.setTooltip(new Tooltip("Write at the caret, or rewrite the selection  (" + Keys.WRITE.getDisplayText() + ")"));
        write.setOnAction(e -> run(ProseRouter.Gesture.APPLY));
        Button ask = new Button("Ask");
        ask.setTooltip(new Tooltip("Ask about the selection or nearby text  (" + Keys.ASK.getDisplayText() + ")"));
        ask.setOnAction(e -> run(ProseRouter.Gesture.ASK));
        write.disableProperty().bind(command.disabledProperty());
        ask.disableProperty().bind(command.disabledProperty());
        wholeDocument.setTooltip(new Tooltip("Ask about the whole document (capped) instead of the selection or nearby text."));

        HBox bar = new HBox(8, command, tier.view(), write, ask, wholeDocument);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(7, 12, 7, 12));
        bar.getStyleClass().add("command-bar");
        return bar;
    }

    private HBox statusBar() {
        statusDot.getStyleClass().add("status-dot");
        Tooltip.install(statusDot, statusDotTip);
        pulse.setFromValue(1);
        pulse.setToValue(0.25);
        pulse.setAutoReverse(true);
        pulse.setCycleCount(Animation.INDEFINITE);
        status.getStyleClass().add("status-message");
        status.setMinWidth(0);
        HBox.setHgrow(status, Priority.SOMETIMES);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        for (Label l : List.of(formatLabel, stats)) {
            l.getStyleClass().add("muted");
            l.setMinWidth(Region.USE_PREF_SIZE);
        }
        formatLabel.setTooltip(new Tooltip("Encoding and line endings: kept exactly as the file had them."));
        usage.getStyleClass().add("chip");
        usage.setMinWidth(Region.USE_PREF_SIZE);
        usageTip.getStyleClass().add("usage-tooltip");
        usageTip.setShowDuration(Duration.seconds(30));
        usage.setTooltip(usageTip);

        HBox bar = new HBox(10, statusDot, status, spacer, formatLabel, divider(), stats, divider(), usage);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 12, 5, 12));
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    private static Separator divider() {
        Separator s = new Separator(Orientation.VERTICAL);
        s.setPrefHeight(14);
        return s;
    }

    // ------------------------------------------------------------------------------------------------
    // Keyboard
    // ------------------------------------------------------------------------------------------------

    /**
     * Scene-wide keys that must work wherever focus is (and that tests can drive by firing key events). Menu
     * accelerators cover the rest.
     */
    private void installShortcuts(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            boolean idle = operations.state() == OperationController.State.IDLE;
            if (e.getCode() == KeyCode.ESCAPE) {
                escape(idle);
                e.consume();
            } else if (e.getCode() == KeyCode.TAB && operations.state() == OperationController.State.REVIEW) {
                operations.accept();
                focusEditor();
                e.consume();
            } else if (Keys.FIND.match(e)) {
                openFind();
                e.consume();
            } else if (Keys.FIND_NEXT.match(e)) {
                findNext();
                e.consume();
            } else if (Keys.FIND_PREVIOUS.match(e)) {
                findPrevious();
                e.consume();
            } else if (Keys.FOCUS_NEXT.match(e) || Keys.FOCUS_PREVIOUS.match(e)) {
                focusCycle.move(Keys.FOCUS_PREVIOUS.match(e));
                e.consume();
            } else if (Keys.FOCUS_EDITOR.match(e)) {
                focusEditor();
                e.consume();
            } else if (Keys.FOCUS_INSTRUCTION.match(e)) {
                focusCommand();
                e.consume();
            } else if (Keys.FOCUS_ANSWER.match(e)) {
                focusSide(side.answerArea());
                e.consume();
            } else if (Keys.FOCUS_NOTES.match(e)) {
                focusSide(side.notesArea());
                e.consume();
            }
        });
    }

    /**
     * Esc, in order of priority: cancel a running request or discard a rewrite; close the find bar when typing in
     * it; return to the text from any other region; close the find bar from the text.
     */
    private void escape(boolean idle) {
        if (!idle) {
            operations.cancelOrReject();
        } else if (owns(find.queryField())) {
            find.close();
        } else if (!owns(editor.area())) {
            focusEditor();
        } else if (find.isOpen()) {
            find.close();
        }
    }

    private void openFind() {
        if (operations.state() == OperationController.State.IDLE) {
            find.open();
        }
    }

    private void findNext() {
        if (operations.state() == OperationController.State.IDLE) {
            find.next();
        }
    }

    private void findPrevious() {
        if (operations.state() == OperationController.State.IDLE) {
            find.previous();
        }
    }

    private void focusCommand() {
        if (!command.isDisabled()) {
            command.requestFocus();
        }
    }

    private void focusSide(Node node) {
        if (!isSidePanelVisible()) {
            setSidePanelVisible(true);
        }
        node.requestFocus();
    }

    /** Focus as the scene sees it; unlike {@code isFocused()} this also holds while the window is not active. */
    private static boolean owns(Node node) {
        return node.getScene() != null && node.getScene().getFocusOwner() == node;
    }

    // ------------------------------------------------------------------------------------------------
    // Appearance
    // ------------------------------------------------------------------------------------------------

    void applyTheme(UiSettings.Theme theme) {
        settings.theme(theme);
        if (root != null) {
            Styles.theme(root, theme);
        }
        darkTheme.setSelected(theme == UiSettings.Theme.DARK);
    }

    void toggleTheme() {
        applyTheme(theme().toggled());
    }

    boolean isSidePanelVisible() {
        return split != null && split.getItems().contains(side.view());
    }

    void setSidePanelVisible(boolean visible) {
        settings.sidePanelVisible(visible);
        sidePanelItem.setSelected(visible);
        if (split == null || visible == isSidePanelVisible()) {
            return;
        }
        if (visible) {
            split.getItems().add(side.view());
            split.setDividerPositions(dividerPosition);
        } else {
            dividerPosition = split.getDividerPositions()[0];
            if (side.view().getScene() != null && isInside(side.view(), side.view().getScene().getFocusOwner())) {
                focusEditor();
            }
            split.getItems().remove(side.view());
        }
    }

    private static boolean isInside(Node container, Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == container) {
                return true;
            }
        }
        return false;
    }

    void zoom(int direction) {
        setFontSize(EditorLayout.zoom(settings.fontSize(), direction));
    }

    void setFontSize(double size) {
        settings.fontSize(size);
        applyEditorFont();
        status(String.format(Locale.ROOT, "Text size %.0f px", settings.fontSize()));
    }

    private void applyEditorFont() {
        UiSettings.EditorFont font = settings.editorFont();
        RadioMenuItem item = fontItems.get(font);
        if (item != null) {
            item.setSelected(true);
        }
        editor.area().setStyle(String.format(Locale.ROOT, "-fx-font-family: %s; -fx-font-size: %.1fpx;",
                font.cssFamily(), settings.fontSize()));
        updateEditorPadding();
    }

    private void installEditorLayout() {
        editor.area().widthProperty().addListener((obs, old, w) -> updateEditorPadding());
        editor.area().addEventFilter(ScrollEvent.SCROLL, e -> {
            if (e.isShortcutDown() && e.getDeltaY() != 0) {
                zoom(e.getDeltaY() > 0 ? 1 : -1);
                e.consume();
            }
        });
    }

    private void updateEditorPadding() {
        double side = EditorLayout.sidePadding(editor.area().getWidth(), settings.fontSize());
        editor.area().setPadding(new Insets(EditorLayout.TOP, side, EditorLayout.BOTTOM, side));
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
        history.record(command.getText());
        focusEditor();
    }

    /** The API key dialog, built but not shown (for tests). */
    Dialog<String> keyDialog() {
        PasswordField field = new PasswordField();
        field.setId("api-key");
        field.setPrefColumnCount(36);
        return new StudioDialog<String>("✱", "Gemini API key",
                "Stays in memory for this session only. Set GEMINI_API_KEY to skip this step.", "Use key")
                .field("API _key", field, "Paste the key from Google AI Studio.")
                .validWhen(field.textProperty().isNotEmpty(),
                        new javafx.beans.property.ReadOnlyStringWrapper("Paste a key first."))
                .result(field::getText)
                .focus(field)
                .build(stage, theme());
    }

    private boolean askForKey() {
        Optional<String> key = keyDialog().showAndWait().map(String::strip).filter(s -> !s.isEmpty());
        key.ifPresent(k -> {
            gateway.setKey(k);
            status("API key set for this session.");
            activity(activity);
        });
        return key.isPresent();
    }

    private void about() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION,
                "A calm writing workspace with a stateless assistant.\n\n"
                        + "Each request sees a fixed window of your text, never a chat history.\n"
                        + "Java 21 · JavaFX · RichTextFX · no browser engine.", ButtonType.OK);
        alert.initOwner(stage);
        alert.setTitle("About Yazı");
        alert.setHeaderText("Yazı");
        Styles.dialog(alert, theme()).showAndWait();
    }

    private void newFile() {
        if (!confirmDiscard()) {
            return;
        }
        editor.load("");
        file = null;
        format = TextFiles.Format.DEFAULT;
        savedRevision = editor.revision();
        updateTitle();
        updateFormat();
    }

    private void open() {
        if (!confirmDiscard()) {
            return;
        }
        File chosen = chooser().showOpenDialog(stage);
        if (chosen != null) {
            openPath(chosen.toPath());
        }
    }

    /** Loads {@code path}, remembering its encoding and line endings. Does not ask about unsaved changes. */
    boolean openPath(Path path) {
        Path absolute = path.toAbsolutePath();
        try {
            TextFiles.Decoded decoded = TextFiles.read(absolute);
            editor.load(decoded.text());
            file = absolute;
            format = decoded.format();
            savedRevision = editor.revision();
            updateTitle();
            updateFormat();
            String how = format.describe();
            status("Opened " + absolute.getFileName() + (how.isEmpty() ? "" : "  (" + how + ")"));
            return true;
        } catch (IOException e) {
            error("Could not open " + absolute.getFileName(), e);
            return false;
        }
    }

    /**
     * A file named on the command line: opened when it exists, otherwise an empty document that saves there
     * (like most editors). Called once at start-up, before anything could be unsaved.
     */
    void openFromCommandLine(String argument) {
        Path path;
        try {
            path = Path.of(argument).toAbsolutePath();
        } catch (java.nio.file.InvalidPathException e) {
            status("Not a valid file name: " + argument);
            return;
        }
        if (Files.exists(path)) {
            openPath(path);
        } else if (path.getParent() != null && Files.isDirectory(path.getParent())) {
            editor.load("");
            file = path;
            format = TextFiles.Format.DEFAULT;
            savedRevision = editor.revision();
            updateTitle();
            updateFormat();
            status("New file: " + path.getFileName() + " (created when you save)");
        } else {
            status("Folder not found: " + path.getParent());
        }
    }

    private boolean save() {
        return (file == null) ? saveAs() : writeTo(file);
    }

    private boolean saveAs() {
        FileChooser chooser = chooser();
        if (file != null && file.getParent() != null && Files.isDirectory(file.getParent())) {
            chooser.setInitialDirectory(file.getParent().toFile());
            chooser.setInitialFileName(file.getFileName().toString());
        }
        File chosen = chooser.showSaveDialog(stage);
        return chosen != null && writeTo(chosen.toPath());
    }

    /** Saves to {@code target} in the document's format, atomically. Package-private for tests. */
    boolean writeTo(Path target) {
        if (editor.isPreviewing()) {
            status("Finish or discard the current AI edit before saving.");
            return false;
        }
        try {
            TextFiles.Encoded written = TextFiles.write(target, editor.area().getText(), format);
            String previousCharset = format.charset().name();
            file = target.toAbsolutePath();
            format = written.format();
            savedRevision = editor.revision();
            updateTitle();
            updateFormat();
            status(written.fellBackToUtf8()
                    ? "Saved " + target.getFileName() + " as UTF-8: the text has characters " + previousCharset
                      + " cannot store."
                    : "Saved " + target.getFileName());
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
        ButtonType answer = Styles.dialog(alert, theme()).showAndWait().orElse(ButtonType.CANCEL);
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

    private void updateFormat() {
        formatLabel.setText(format.shortLabel());
    }

    /** Word/character count of the document, or of the selection when there is one. Frozen during AI preview. */
    private void updateStats() {
        if (editor.isPreviewing()) {
            return;
        }
        String selected = editor.area().getSelectedText();
        stats.setText(selected.isEmpty() ? documentStats.describe()
                : DocumentStats.of(selected).describeSelection(documentStats));
    }

    private void updateUsage() {
        usage.setText(meter.chipText());
        usageTip.setText(meter.details());
    }

    void resetUsage() {
        meter.reset();
        updateUsage();
        status("Usage counter reset.");
    }

    private void error(String message, IOException e) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message + "\n" + TextFiles.describe(e), ButtonType.OK);
        alert.initOwner(stage);
        Styles.dialog(alert, theme()).showAndWait();
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
    // Messages from the recovery layer
    // ------------------------------------------------------------------------------------------------

    static String retryMessage(int attempt, int maxAttempts, java.time.Duration wait, GatewayException cause) {
        String what = switch (cause.kind()) {
            case RATE_LIMITED -> "Rate limit reached";
            case NETWORK -> "Network problem";
            default -> "The model service had a problem" + (cause.httpStatus() > 0 ? " (HTTP " + cause.httpStatus() + ")" : "");
        };
        return what + "; retrying in " + OperationController.humanDuration(Math.max(1, wait.toSeconds()))
                + " (attempt " + attempt + " of " + maxAttempts + ")…  Esc to cancel";
    }

    static String fallbackMessage(String requested, String fallback) {
        return "Model “" + requested + "” is not available; using “" + fallback + "” instead.";
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
    public void showOutput(String title, String text) {
        if (!isSidePanelVisible()) {
            setSidePanelVisible(true);
        }
        side.showOutput(title, text);
    }

    @Override
    public void status(String message) {
        status.setText(message);
    }

    @Override
    public void cost(Usage usage, int windowChars) {
        meter.record(usage, windowChars);
        updateUsage();
    }

    @Override
    public void activity(OperationController.Activity value) {
        activity = value;
        statusDot.getStyleClass().removeAll("idle", "ready", "working", "review", "error");
        String cls;
        String tip;
        switch (value) {
            case WORKING -> {
                cls = "working";
                tip = "Working…  (Esc to cancel)";
            }
            case REVIEW -> {
                cls = "review";
                tip = "Waiting for your review: Tab accepts, Esc discards.";
            }
            case ERROR -> {
                cls = "error";
                tip = "The last request failed; see the message.";
            }
            default -> {
                cls = gateway.hasKey() ? "ready" : "idle";
                tip = gateway.hasKey() ? "Assistant ready." : "No API key: File → API key…";
            }
        }
        statusDot.getStyleClass().add(cls);
        statusDotTip.setText(tip);
        if (value == OperationController.Activity.WORKING) {
            pulse.playFromStart();
        } else {
            pulse.stop();
            statusDot.setOpacity(1);
        }
    }
}
