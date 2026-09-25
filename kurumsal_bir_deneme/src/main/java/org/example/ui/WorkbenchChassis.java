package org.example.ui;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.example.admin.AuditLog;
import org.example.core.AnswerModel.AnswerException;
import org.example.core.AnswerModel.TokenSink;
import org.example.core.Workbench;
import org.example.input.KeyMapRegistry;
import org.example.input.KeyMapRegistry.Action;
import org.example.input.KeyMapRegistry.BindResult;
import org.example.input.KeyMapRegistry.KeyStroke;
import org.example.model.BinaryAsset;
import org.example.model.DocumentRecord;
import org.example.model.SearchResult;
import org.example.net.WebSearchBridge;
import org.example.net.WebSearchBridge.HybridOutcome;
import org.example.platform.ClipboardPort;
import org.example.platform.OsShellBridge.ShellResult;
import org.example.preview.PreviewResult;
import org.example.project.ProjectProfile;
import org.example.project.ProjectWorkspaceManager.ActiveProject;
import org.example.project.ProjectWorkspaceManager.SwitchReport;
import org.example.repl.InternalTerminalEngine;
import org.example.state.FocusModeCoordinator;
import org.example.state.FocusModeCoordinator.Element;
import org.example.state.FocusModeCoordinator.FocusState;
import org.example.state.FocusModeCoordinator.NotificationPolicy;
import org.example.state.FocusModeCoordinator.Origin;
import org.example.state.FocusModeCoordinator.Severity;
import org.example.state.PanelStateCoordinator;
import org.example.state.PanelStateCoordinator.Panel;
import org.example.state.PanelStateCoordinator.PanelState;
import org.example.state.Subscription;
import org.example.state.ViewModeCoordinator.MemorySnapshot;
import org.example.state.ViewModeCoordinator.ViewMode;
import org.example.ui.CommandBarView.Mode;
import org.example.ui.ResultListView.SourceChip;
import org.example.ui.WorkspaceBrowserView.Place;
import org.example.ui.WorkspaceBrowserView.SortKey;
import org.example.workbench.ExtendedWorkbenchController;
import org.example.workbench.ExtendedWorkbenchController.QueryOutcome;
import org.example.workbench.WorkbenchController;
import org.example.workbench.WorkbenchController.RemovalResult;
import org.example.workbench.WorkbenchController.WorkbenchEvent;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The JavaFX chassis: root layout, panel docking, focus mode, the unified key dispatcher and query routing, all bound
 * to whichever project {@link ExtendedWorkbenchController} currently has active.
 *
 * <h2>Layout</h2>
 * <pre>
 * StackPane (shell) ─┬─ BorderPane.workbench-root
 *                    │   ├─ top     CommandBarView (← → ↑ ⟳, breadcrumbs, search field, focus switch;
 *                    │   │                          toolbar row: Yeni · Sırala · Görünüm · ⋯ ··· Terminal)
 *                    │   ├─ center  StackPane ─┬─ workSplit (horizontal, .main-split)
 *                    │   │                     │   ├─ FILE_TREE  FileTreePanelView (projects, root folders, counter)
 *                    │   │                     │   └─ contentSplit (vertical, .main-split)
 *                    │   │                     │       ├─ RESULTS   WorkspaceBrowserView (Home | explorer | results)
 *                    │   │                     │       └─ TERMINAL  TerminalPanelView           (Ctrl+J)
 *                    │   │                     └─ ToastNotifier overlay
 *                    │   └─ bottom  status bar (command guide button, project, item count, mode, message)
 *                    └─ [QuickLookOverlay ─ scrim + PreviewPaneView card]   only while a preview is open
 * </pre>
 *
 * <p>The terminal lives under the content only: while it is hidden the browser's table reaches the bottom of the
 * window; {@code Ctrl+J} splits the content column vertically, table above, terminal below.</p>
 *
 * <h2>Navigation</h2>
 * The {@link WorkspaceBrowserView} owns the back/forward history (Home dashboard, a folder, the search results);
 * this class mirrors it into the breadcrumb trail, the navigation buttons, the left panel's highlight and the status
 * bar's item count. A submitted search, question or web query navigates to the results view, whose slot holds the
 * {@link ResultListView}.
 *
 * <h2>Focus mode</h2>
 * Focus mode strips the window down to the header and the workspace. The sidebar and the terminal leave the layout
 * through the {@link PanelStateCoordinator} (a {@code SplitPane} item has to be removed, not merely hidden, or its
 * divider keeps the space); the action bar, the "Hızlı erişim" card and the status bar go {@code visible = false}
 * with {@code managed = false}, so they give up their space instead of leaving an empty gap. What is left — the
 * engine picker, the search input, the focus switch, and the browser with its table, inline results and previews —
 * expands to the full width and height of the window.
 *
 * <p>Nothing is reparented: every node stays in the slot it was built into and only its visibility changes. That is
 * what makes the toggle cheap and reversible — the input keeps keyboard focus, caret and selection, the table keeps
 * its column widths, and the split dividers come back at the positions the coordinator remembered. {@code Esc}
 * leaves focus mode once the search field is empty and no preview is open.
 *
 * <h2>Preview</h2>
 * Previews open as a Quick Look card centred over the whole window ({@code Space} on a result, the preview shortcut,
 * or the toolbar toggle). Closing it ({@code Esc}, {@code Space}, the close button or a click on the scrim)
 * unmounts the overlay, clears the card and calls {@code PreviewService#dismiss()} in the same pulse.
 *
 * <h2>Docking</h2>
 * The {@link PanelStateCoordinator} owns visibility; this class only mirrors it. A hidden panel's node is removed from
 * its {@code SplitPane} (not made invisible), so it costs no CSS or layout pass; the node instance is kept and
 * re-inserted at its slot on re-dock with the divider position the coordinator remembered. {@code contentSplit}
 * itself leaves {@code workSplit} when both of its panels are gone. A detached panel moves into a small owned window;
 * closing that window docks it again.
 *
 * <h2>Actions</h2>
 * Every capability is reachable by click as well as by key: the toolbar holds "Yeni" (files, folder, project),
 * "Sırala", "Görünüm" (navigation pane, terminal, IT view, focus mode), "⋯" (save, project switch, shortcuts,
 * administration, command guide) and the terminal toggle; the focus-mode switch sits right of the input so it stays
 * reachable in focus mode. Document actions (open, reveal, preview, copy path/content, untrack, delete from disk) are
 * shared by the browser and the result list and are resolved here: this class owns the confirmation dialog and the
 * system clipboard, the controller owns the safety checks ({@link WorkbenchController#purge}). Custom folder-card
 * titles are kept per project in {@value #TITLES_FILE}.
 *
 * <h2>Threading</h2>
 * Coordinators notify on the thread that caused a change (search threads, the terminal thread, project switches);
 * every listener here hops to the FX thread through {@link Fx}. Blocking work — queries, ingestion, key file reload —
 * runs on virtual threads.
 */
public final class WorkbenchChassis implements AutoCloseable {

    private static final Duration HEAP_TICK = Duration.seconds(2);
    /** Per-project custom folder-card titles ({@code <absolute folder path> = <title>}), in the project folder. */
    static final String TITLES_FILE = "folder-titles.properties";
    /** Per-project UI preferences, in the project folder. */
    static final String UI_SETTINGS_FILE = "ui-settings.properties";
    /** {@code user_actions_only} (default) or {@code all} — what quiet mode lets through in focus mode. */
    static final String NOTIFICATIONS_KEY = "focus.notifications";
    /** How many refusal reasons one import summary spells out before it falls back to a count. */
    private static final int MAX_REPORTED_REASONS = 3;

    private final ExtendedWorkbenchController ext;
    private final Stage stage;

    private final BorderPane root = new BorderPane();
    private final StackPane shell = new StackPane(root);
    private final StackPane center = new StackPane();
    private final SplitPane workSplit = new SplitPane();
    private final SplitPane contentSplit = new SplitPane();

    private final CommandBarView commandBar = new CommandBarView();
    private final ResultListView results;
    private final FileTreePanelView fileTree;
    private final WorkspaceBrowserView browser;
    private final TerminalPanelView terminalView = new TerminalPanelView();
    private final PreviewPaneView previewPane = new PreviewPaneView();
    private final QuickLookOverlay quickLook = new QuickLookOverlay(shell, previewPane);
    private CommandGuideDialog commandGuide;
    private final ToastNotifier toasts = new ToastNotifier();

    private final HBox statusBar = new HBox(14);
    private final Label statusProject = statusLabel();
    private final Label statusDocs = statusLabel();
    private final Label statusMode = statusLabel();
    private final Label statusHeap = statusLabel();
    private final Label statusMessage = new Label();
    /** Large LAN downloads in progress, keyed by short content id; touched on the FX thread only. */
    private final Map<String, TransferState> transfers = new LinkedHashMap<>();
    private final ProgressBar transferBar = new ProgressBar(0);
    private final Label transferLabel = statusLabel();
    private final HBox statusTransfer = new HBox(6, transferBar, transferLabel);
    private final Tooltip transferTip = new Tooltip();
    private final Timeline heapTicker = new Timeline(new KeyFrame(HEAP_TICK, e -> updateHeap()));
    /** LAN security badge: zero-trust / legacy / off; click opens the device management page. */
    private final Label statusSecurity = statusLabel();
    private final Tooltip securityTip = new Tooltip();
    /** Lock (or warning) for the last finished LAN transfer, shown for a few seconds. */
    private final Label statusSecureTransfer = statusLabel();
    private final Tooltip secureTransferTip = new Tooltip();
    private final javafx.animation.PauseTransition secureTransferHide =
            new javafx.animation.PauseTransition(javafx.util.Duration.seconds(8));
    private final Timeline securityTicker = new Timeline(new KeyFrame(javafx.util.Duration.seconds(2),
            e -> updateSecurityBadge()));

    // Toolbar (chrome) and the always-visible focus switch.
    private final MenuButton newMenu = new MenuButton("+ Yeni");
    private final MenuButton sortMenu = new MenuButton("⇅ Sırala");
    private final MenuButton viewMenu = new MenuButton("☰ Görünüm");
    private final MenuButton moreMenu = new MenuButton("⋯");
    private final Menu projectMenu = new Menu("Proje Değiştir");
    private final CheckMenuItem treeItem = new CheckMenuItem("Gezinti bölmesi");
    private final CheckMenuItem terminalItem = new CheckMenuItem("Terminal");
    private final CheckMenuItem detailItem = new CheckMenuItem("Detaylı (IT) görünüm");
    private final CheckMenuItem focusItem = new CheckMenuItem("Odak modu");
    private final MenuItem saveItem = new MenuItem("İndeksi Kaydet");
    private final EnumMap<SortKey, RadioMenuItem> sortItems = new EnumMap<>(SortKey.class);
    private final RadioMenuItem ascendingItem = new RadioMenuItem("Artan");
    private final RadioMenuItem descendingItem = new RadioMenuItem("Azalan");
    private final ToggleButton terminalToggle = toggleButton("▭ Terminal", () -> perform(Action.TOGGLE_TERMINAL));
    private final Button guideButton = toolButton("📖 Komut Rehberi", this::showCommandGuide);
    private final ToggleButton focusToggle = toggleButton("Odak Modu", () -> perform(Action.TOGGLE_FOCUS));

    /** Custom folder-card titles of the active project; written on rename, read on activation. */
    private final Map<Path, String> folderTitles = new ConcurrentHashMap<>();
    private volatile Path titlesFile;
    private volatile Path settingsFile;
    /** What {@link #UI_SETTINGS_FILE} currently holds, so loading a project never rewrites the file it just read. */
    private volatile NotificationPolicy persistedPolicy;

    /** System clipboard; the FX clipboard may only be touched on the FX thread. */
    private final ClipboardPort clipboard = text -> Fx.run(() -> {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    });

    private final EnumMap<Panel, Region> panelNodes = new EnumMap<>(Panel.class);
    private final EnumMap<Panel, Stage> detachedWindows = new EnumMap<>(Panel.class);
    private final List<Subscription> projectBindings = new ArrayList<>();
    private final List<Subscription> lifetime = new ArrayList<>();
    private final Fx.Coalescer treeRefresh = new Fx.Coalescer(this::refreshTree);
    private final AtomicLong queryGeneration = new AtomicLong();
    private final AtomicInteger bulkIngests = new AtomicInteger();

    private volatile WorkbenchController controller;
    private volatile FocusModeCoordinator focus;
    private long previewRequest;
    private boolean closed;

    private final WorkbenchController.UiHooks hooks = new WorkbenchController.UiHooks() {
        @Override
        public void focusSearch() {
            Fx.run(commandBar::focusInput);
        }
    };

    public WorkbenchChassis(ExtendedWorkbenchController ext, Stage stage) {
        this.ext = Objects.requireNonNull(ext, "ext must not be null");
        this.stage = Objects.requireNonNull(stage, "stage must not be null");
        this.results = new ResultListView(new ResultActions());
        this.fileTree = new FileTreePanelView(new TreeActions());
        this.browser = new WorkspaceBrowserView(new BrowserActions());
        buildLayout();

        // Registered before ExtendedWorkbenchController#start, so the very first activation is observed too. The
        // extended controller registered its own attach/detach first: its FocusModeCoordinator exists when ours runs.
        lifetime.add(ext.projects().onActivated(this::activated));
        lifetime.add(ext.projects().onDeactivating(this::deactivating));
        ext.setNotifier(toasts);
        ext.setTransferObserver(new ExtendedWorkbenchController.TransferObserver() {
            @Override
            public void progress(String what, String peer, long transferred, long total) {
                Fx.run(() -> showTransfer(what, peer, transferred, total));
            }

            @Override
            public void finished(String what, String peer, boolean verified) {
                Fx.run(() -> endTransfer(what, verified));
            }

            @Override
            public void completed(boolean outgoing, String name, String peer, long bytes, boolean encrypted) {
                Fx.run(() -> showSecureTransfer(outgoing, name, peer, bytes, encrypted));
            }
        });
    }

    // ================================================================== layout

    private void buildLayout() {
        root.getStyleClass().add("workbench-root");

        workSplit.setOrientation(Orientation.HORIZONTAL);
        workSplit.getStyleClass().add("main-split");
        contentSplit.setOrientation(Orientation.VERTICAL);
        contentSplit.getStyleClass().addAll("main-split", "content-split");
        browser.resultsSlot().getChildren().add(results);

        panelNodes.put(Panel.FILE_TREE, fileTree);
        panelNodes.put(Panel.RESULTS, browser);
        panelNodes.put(Panel.TERMINAL, terminalView);
        // Side panels keep their size when the window is resized; the browser absorbs the change.
        SplitPane.setResizableWithParent(fileTree, false);
        SplitPane.setResizableWithParent(terminalView, false);

        StackPane.setAlignment(toasts, Pos.BOTTOM_RIGHT);
        center.getChildren().addAll(workSplit, toasts);

        statusBar.getStyleClass().add("status-bar");
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(4, 12, 4, 12));
        statusMessage.getStyleClass().add("status-message");
        statusMessage.setMinWidth(0);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        guideButton.getStyleClass().add("guide-button");
        guideButton.setTooltip(new Tooltip("Komut rehberi: arama sözdizimi, AI, web, terminal ve kısayollar (F1)"));
        statusTransfer.getStyleClass().add("status-transfer");
        statusTransfer.setAlignment(Pos.CENTER_LEFT);
        statusTransfer.setMinWidth(Region.USE_PREF_SIZE);
        transferBar.setPrefWidth(120);
        Tooltip.install(statusTransfer, transferTip);
        statusTransfer.setVisible(false);
        statusTransfer.setManaged(false);
        statusSecurity.getStyleClass().addAll("security-badge", "status-clickable");
        statusSecurity.setOnMouseClicked(e -> showAdminPanel(true));
        Tooltip.install(statusSecurity, securityTip);
        statusSecureTransfer.getStyleClass().add("secure-transfer");
        Tooltip.install(statusSecureTransfer, secureTransferTip);
        statusSecureTransfer.setVisible(false);
        statusSecureTransfer.setManaged(false);
        secureTransferHide.setOnFinished(e -> {
            statusSecureTransfer.setVisible(false);
            statusSecureTransfer.setManaged(false);
        });
        statusBar.getChildren().addAll(guideButton, statusProject, statusDocs, statusMode, spacer, statusSecureTransfer,
                statusTransfer, statusSecurity, statusMessage);
        heapTicker.setCycleCount(Animation.INDEFINITE);
        securityTicker.setCycleCount(Animation.INDEFINITE);
        updateSecurityBadge();
        securityTicker.play();

        root.setTop(commandBar);
        root.setCenter(center);
        root.setBottom(statusBar);

        commandBar.setOnSubmit(this::submit);
        commandBar.setOnLeaveDown(() -> {
            if (browser.place() instanceof WorkspaceBrowserView.Results) {
                results.focusList();
            } else {
                browser.focusContent();
            }
        });
        commandBar.setNavigation(new CommandBarView.Navigation() {
            @Override
            public void back() {
                browser.back();
            }

            @Override
            public void forward() {
                browser.forward();
            }

            @Override
            public void up() {
                browser.up();
            }

            @Override
            public void refresh() {
                refreshAll();
            }
        });
        browser.setOnPlaceChanged(this::placeChanged);
        // The chooser must stay modal to the main window even when the tree panel is undocked (focus mode) or hidden.
        fileTree.setOwnerFallback(stage);
        buildToolbar();
        commandBar.setInputDisabled(true);
        statusMode.getStyleClass().add("status-clickable");
        statusMode.setTooltip(new Tooltip("Basit / IT görünümü arasında geçiş"));
        statusMode.setOnMouseClicked(e -> perform(Action.TOGGLE_MODE));
        terminalView.setOnHide(() -> withController(c -> c.panels().hide(Panel.TERMINAL)));
        previewPane.setOnClose(this::closePreview);
        previewPane.setOnOpen(this::openPreviewed);
        quickLook.setOnDismissRequest(this::closePreview);
        status("Proje yükleniyor…");
    }

    private void buildToolbar() {
        for (MenuButton m : List.of(newMenu, sortMenu, viewMenu, moreMenu)) {
            m.getStyleClass().addAll("ghost-button", "toolbar-menu");
            m.setFocusTraversable(false);
        }
        newMenu.getStyleClass().add("accent-button");
        newMenu.setTooltip(new Tooltip("Belge, klasör veya proje ekleyin (sürükle-bırak da çalışır)"));
        newMenu.getItems().setAll(
                menuItem("Dosya Ekle…", this::addFiles),
                menuItem("Klasör Ekle…", fileTree::chooseFolder),
                new SeparatorMenuItem(),
                menuItem("Yeni Proje…", this::newProject));

        ToggleGroup keys = new ToggleGroup();
        List<MenuItem> sortEntries = new ArrayList<>();
        for (SortKey k : SortKey.values()) {
            RadioMenuItem item = new RadioMenuItem(k.label());
            item.setToggleGroup(keys);
            item.setOnAction(e -> browser.sortBy(k, !descendingItem.isSelected()));
            sortItems.put(k, item);
            sortEntries.add(item);
        }
        ToggleGroup direction = new ToggleGroup();
        ascendingItem.setToggleGroup(direction);
        descendingItem.setToggleGroup(direction);
        ascendingItem.setSelected(true);
        ascendingItem.setOnAction(e -> resort(true));
        descendingItem.setOnAction(e -> resort(false));
        sortEntries.add(new SeparatorMenuItem());
        sortEntries.add(ascendingItem);
        sortEntries.add(descendingItem);
        sortMenu.getItems().setAll(sortEntries);
        sortMenu.setTooltip(new Tooltip("Listeyi sırala (sütun başlıklarına tıklamak da sıralar)"));
        sortMenu.setOnShowing(e -> syncSortMenu());

        treeItem.setOnAction(e -> perform(Action.TOGGLE_FILE_TREE));
        terminalItem.setOnAction(e -> perform(Action.TOGGLE_TERMINAL));
        detailItem.setOnAction(e -> perform(Action.TOGGLE_MODE));
        focusItem.setOnAction(e -> perform(Action.TOGGLE_FOCUS));
        viewMenu.getItems().setAll(treeItem, terminalItem, new SeparatorMenuItem(), detailItem, focusItem);
        viewMenu.setOnShowing(e -> syncToggles());

        saveItem.setOnAction(e -> saveIndex());
        projectMenu.getItems().add(new MenuItem("…")); // a Menu with no items never opens its submenu
        moreMenu.getItems().setAll(saveItem, projectMenu, new SeparatorMenuItem(),
                menuItem("Klavye Kısayolları…", this::showShortcutManager),
                menuItem("Yönetim ve Tanılama…", () -> showAdminPanel(false)),
                menuItem("Komut Rehberi  (F1)", this::showCommandGuide));
        moreMenu.setTooltip(new Tooltip("Diğer işlemler"));
        moreMenu.setOnShowing(e -> rebuildProjectMenu());

        terminalToggle.getStyleClass().add("terminal-toggle");
        focusToggle.getStyleClass().add("focus-toggle");
        commandBar.setToolbarItems(List.of(newMenu, divider(), sortMenu, viewMenu, moreMenu,
                CommandBarView.spacer(), terminalToggle));
        commandBar.setTrailingItems(List.of(focusToggle));
    }

    private static MenuItem menuItem(String text, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(e -> action.run());
        return item;
    }

    private void resort(boolean ascending) {
        SortKey key = browser.sortState().map(Map.Entry::getKey).orElse(SortKey.NAME);
        browser.sortBy(key, ascending);
    }

    private void syncSortMenu() {
        Optional<Map.Entry<SortKey, Boolean>> state = browser.sortState();
        sortItems.values().forEach(i -> i.setSelected(false));
        state.ifPresent(s -> {
            sortItems.get(s.getKey()).setSelected(true);
            (s.getValue() ? ascendingItem : descendingItem).setSelected(true);
        });
        sortMenu.getItems().forEach(i -> i.setDisable(browser.place() instanceof WorkspaceBrowserView.Results));
    }

    private static Button toolButton(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("ghost-button");
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
        return b;
    }

    private static ToggleButton toggleButton(String text, Runnable action) {
        ToggleButton b = new ToggleButton(text);
        b.getStyleClass().add("tool-toggle");
        b.setFocusTraversable(false);
        // The coordinators own the state: the click only requests a change, the listeners set the real selection.
        b.setOnAction(e -> action.run());
        return b;
    }

    private static Node divider() {
        Separator s = new Separator(Orientation.VERTICAL);
        s.getStyleClass().add("toolbar-divider");
        return s;
    }

    private void perform(Action action) {
        withController(c -> c.perform(action));
        syncToggles();
    }

    /** Re-reads every toggle's state from its coordinator (also undoes a click the coordinator refused). */
    private void syncToggles() {
        WorkbenchController c = controller;
        FocusModeCoordinator f = focus;
        treeItem.setSelected(c != null && c.panels().isShown(Panel.FILE_TREE));
        terminalToggle.setSelected(c != null && c.panels().isShown(Panel.TERMINAL));
        terminalItem.setSelected(terminalToggle.isSelected());
        detailItem.setSelected(c != null && c.view().mode() == ViewMode.DETAILED);
        focusToggle.setSelected(f != null && f.focusActive());
        focusItem.setSelected(focusToggle.isSelected());
    }

    private static Label statusLabel() {
        Label l = new Label();
        l.getStyleClass().add("status-item");
        l.setMinWidth(Region.USE_PREF_SIZE);
        return l;
    }

    public Parent root() {
        return shell;
    }

    /** Wires the scene-level key dispatcher and key-file hot reload; call once after the scene exists. */
    public void install(Scene scene) {
        scene.setOnKeyPressed(this::onKeyPressed);
        // A filter: the result list would otherwise consume Space for its own selection handling.
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::onSpace);
        stage.focusedProperty().addListener((obs, was, now) -> {
            if (now) {
                reloadKeysIfChanged();
            }
        });
    }

    // ================================================================== project binding

    private void activated(ActiveProject project) {
        Fx.run(() -> bind(project));
    }

    private void deactivating(ActiveProject project) {
        // Listener handles are thread-safe; close them on the switching thread so nothing from the outgoing
        // project reaches the UI after this point. View cleanup follows on the FX thread.
        closeProjectBindings();
        Fx.run(this::unbindViews);
    }

    private void bind(ActiveProject project) {
        WorkbenchController c = project.controller();
        boolean stillActive = ext.projects().active().map(a -> a.controller() == c).orElse(false);
        if (closed || !stillActive) {
            return; // switched again before this pulse ran
        }
        FocusModeCoordinator f;
        try {
            f = ext.focus();
        } catch (IllegalStateException e) {
            return;
        }
        closeProjectBindings();
        unbindViews();
        controller = c;
        focus = f;
        toasts.setGate(f);
        c.setUiHooks(hooks);
        c.setClipboard(clipboard); // 'cite' copies straight to the system clipboard

        synchronized (projectBindings) {
            // Replaces the extended controller's preview handler: that one only reports non-ready results, the
            // chassis renders every outcome in the dock.
            projectBindings.add(c.overrideAction(Action.PREVIEW_SELECTED,
                    () -> Fx.run(() -> previewRow(c.selectedRow()))));
            // Ctrl+O. The only ingest pathway besides drag & drop that needs no visible chrome, so it is also the
            // one focus mode relies on.
            projectBindings.add(c.overrideAction(Action.ADD_FILES, () -> Fx.run(this::addFiles)));
            projectBindings.add(c.panels().onChange((previous, current) ->
                    Fx.run(() -> applyPanel(current.panel()))));
            projectBindings.add(f.onChange((previous, current) -> Fx.run(() -> applyFocus(previous, current))));
            projectBindings.add(c.view().onModeChanged((previous, current) -> Fx.run(this::updateModeChrome)));
            projectBindings.add(c.keys().onChange(bindings -> Fx.run(this::applyBindings)));
            projectBindings.add(f.onNotificationPolicyChanged(this::saveNotificationPolicy));
            projectBindings.add(c.onEvent(this::onWorkbenchEvent));
        }

        // Owned by the UI, not the controller: the chooser needs the FX thread and a window to be modal to.
        // Re-registering on every activation is harmless — the engine keeps one spec per name.
        c.terminal().register(new InternalTerminalEngine.CommandSpec("add", "add [<path>]",
                "Index a document or folder; with no path the file chooser opens", Set.of(), this::cmdAdd));
        c.terminal().alias("ingest", "add");

        results.bind(c.view());
        terminalView.bind(c.terminal());
        folderTitles.clear();
        titlesFile = project.profile().root().resolve(TITLES_FILE);
        settingsFile = project.profile().root().resolve(UI_SETTINGS_FILE);
        browser.reset(); // the previous project's folders and results mean nothing here
        refreshTree();
        loadFolderTitles(titlesFile);
        loadNotificationPolicy(settingsFile, f);
        refreshProjects();
        for (Panel p : Panel.values()) {
            applyPanel(p);
        }
        applyFocus(null, f.state());
        applyBindings();
        updateModeChrome();
        commandBar.setInputDisabled(false);
        statusProject.setText(project.profile().name());
        syncToggles();
        commandBar.focusInput();
    }

    /** Reads the project's custom card titles off the FX thread; the tree is refreshed once they are in. */
    private void loadFolderTitles(Path file) {
        Thread.ofVirtual().name("dwb-ui-titles").start(() -> {
            Map<Path, String> loaded = new HashMap<>();
            if (Files.isRegularFile(file)) {
                Properties p = new Properties();
                try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    p.load(in);
                    p.forEach((k, v) -> {
                        String title = String.valueOf(v).strip();
                        if (!title.isEmpty()) {
                            loaded.put(Path.of(String.valueOf(k)).toAbsolutePath().normalize(), title);
                        }
                    });
                } catch (IOException | RuntimeException e) {
                    toasts.post(Severity.INFO, "Klasör başlıkları okunamadı: " + e.getMessage());
                }
            }
            Fx.run(() -> {
                if (file.equals(titlesFile)) {
                    folderTitles.putAll(loaded);
                    refreshTree();
                }
            });
        });
    }

    /**
     * Reads {@link #UI_SETTINGS_FILE} and applies the project's notification policy. A missing file, an unreadable
     * one or an unknown value all mean the default ({@code user_actions_only}) — a preference is never worth
     * refusing to open a project over.
     */
    private void loadNotificationPolicy(Path file, FocusModeCoordinator coordinator) {
        Thread.ofVirtual().name("dwb-ui-settings").start(() -> {
            NotificationPolicy policy = NotificationPolicy.USER_ACTIONS_ONLY;
            if (Files.isRegularFile(file)) {
                Properties p = new Properties();
                try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    p.load(in);
                    policy = NotificationPolicy.parse(p.getProperty(NOTIFICATIONS_KEY)).orElse(policy);
                } catch (IOException | RuntimeException e) {
                    toasts.post(Severity.INFO, "Arayüz ayarları okunamadı: " + e.getMessage());
                }
            }
            if (file.equals(settingsFile)) { // still the project we loaded this for
                persistedPolicy = policy;    // set first: this value came from the file, so do not write it back
                coordinator.setNotificationPolicy(policy);
            }
        });
    }

    /** Persists a policy the user changed at runtime ({@code focus --notifications …}). */
    private void saveNotificationPolicy(NotificationPolicy policy) {
        Path file = settingsFile;
        if (file == null || policy == persistedPolicy) {
            return;
        }
        persistedPolicy = policy;
        Thread.ofVirtual().name("dwb-ui-settings-save").start(() -> {
            Properties p = new Properties();
            if (Files.isRegularFile(file)) {
                try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    p.load(in); // keep any other settings in the file
                } catch (IOException | RuntimeException ignored) {
                    // an unreadable file is replaced rather than allowed to swallow the new setting
                }
            }
            p.setProperty(NOTIFICATIONS_KEY, policy.id());
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                p.store(out, "Document Workbench - UI settings");
            } catch (IOException e) {
                toasts.post(Severity.WARNING, "Arayüz ayarı kaydedilemedi: " + e.getMessage());
            }
        });
    }

    private void saveFolderTitles() {
        Path file = titlesFile;
        if (file == null) {
            return;
        }
        Map<Path, String> snapshot = Map.copyOf(folderTitles);
        Thread.ofVirtual().name("dwb-ui-titles-save").start(() -> {
            Properties p = new Properties();
            snapshot.forEach((k, v) -> p.setProperty(k.toString(), v));
            // Written to a temp file and renamed, so a crash mid-write never truncates the existing titles.
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try {
                try (Writer out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                    p.store(out, "Document Workbench - folder card titles");
                }
                try {
                    Files.move(temp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                toasts.post(Severity.WARNING, "Klasör başlığı kaydedilemedi: " + e.getMessage());
            }
        });
    }

    /** Lists the projects (a directory scan) off the FX thread and hands them to the navigation pane. */
    private void refreshProjects() {
        Thread.ofVirtual().name("dwb-ui-projects").start(() -> {
            List<ProjectProfile> all;
            try {
                all = ext.projects().list();
            } catch (RuntimeException e) {
                all = List.of();
            }
            String activeId = ext.projects().active().map(a -> a.profile().id()).orElse("");
            List<ProjectProfile> listed = new ArrayList<>(all);
            ext.projects().active().map(ActiveProject::profile)
                    .filter(p -> listed.stream().noneMatch(q -> q.id().equals(p.id())))
                    .ifPresent(p -> listed.addFirst(p));
            Fx.run(() -> fileTree.setProjects(listed, activeId));
        });
    }

    private void closeProjectBindings() {
        synchronized (projectBindings) {
            projectBindings.forEach(Subscription::close);
            projectBindings.clear();
        }
    }

    private void unbindViews() {
        if (controller != null) {
            rememberDividers();
        }
        closePreview();
        controller = null;
        focus = null;
        toasts.setGate(null);
        queryGeneration.incrementAndGet();
        results.unbind();
        terminalView.unbind();
        commandBar.setInputDisabled(true);
        for (Panel p : Panel.values()) {
            closeDetached(p);
        }
    }

    /** Reported by the application once {@link ExtendedWorkbenchController#start()} returned. */
    public void startupFinished(SwitchReport report) {
        Fx.run(() -> status(report.describe()));
    }

    public void startupFailed(Throwable error) {
        Fx.run(() -> {
            status("Başlatılamadı: " + error.getMessage());
            toasts.post(Severity.CRITICAL, "Çalışma alanı açılamadı: " + error.getMessage());
        });
    }

    private void withController(java.util.function.Consumer<WorkbenchController> action) {
        WorkbenchController c = controller;
        if (c != null) {
            action.accept(c);
        }
    }

    // ================================================================== docking

    /** Mirrors the coordinator's current state of {@code panel}; always reads the latest state, not the event. */
    private void applyPanel(Panel panel) {
        WorkbenchController c = controller;
        if (c == null) {
            return;
        }
        PanelState state = c.panels().state(panel);
        rememberDividers();
        Region node = panelNodes.get(panel);
        switch (state.visibility()) {
            case DOCKED -> {
                closeDetached(panel);
                mount(panel, node);
            }
            case HIDDEN -> {
                closeDetached(panel);
                host(panel).getItems().remove(node);
            }
            case DETACHED -> {
                host(panel).getItems().remove(node);
                openDetached(panel, node);
            }
        }
        syncWorkSplit();
        applyDividers();
        syncToggles();
    }

    private SplitPane host(Panel panel) {
        return panel == Panel.FILE_TREE ? workSplit : contentSplit;
    }

    private void mount(Panel panel, Region node) {
        SplitPane host = host(panel);
        if (host.getItems().contains(node)) {
            return;
        }
        if (panel == Panel.TERMINAL) {
            host.getItems().add(node);      // the terminal is always below the content
        } else {
            host.getItems().addFirst(node); // the tree left of the content; the browser above the terminal
        }
    }

    /** {@code contentSplit} is itself only in the layout while it holds at least one panel. */
    private void syncWorkSplit() {
        boolean mounted = workSplit.getItems().contains(contentSplit);
        if (contentSplit.getItems().isEmpty()) {
            if (mounted) {
                workSplit.getItems().remove(contentSplit);
            }
        } else if (!mounted) {
            workSplit.getItems().add(contentSplit);
        }
    }

    /** Stores the current divider positions in the coordinator before any structural change. */
    private void rememberDividers() {
        WorkbenchController c = controller;
        if (c == null || root.getWidth() <= 0) {
            return; // not laid out yet: the positions are still the coordinator's own
        }
        PanelStateCoordinator panels = c.panels();
        if (workSplit.getItems().size() == 2 && workSplit.getWidth() > 0) {
            panels.rememberDivider(Panel.FILE_TREE, workSplit.getDividerPositions()[0]);
        }
        if (contentSplit.getItems().size() == 2 && contentSplit.getHeight() > 0) {
            panels.rememberDivider(Panel.TERMINAL, contentSplit.getDividerPositions()[0]);
        }
    }

    private void applyDividers() {
        WorkbenchController c = controller;
        if (c == null) {
            return;
        }
        PanelStateCoordinator panels = c.panels();
        if (workSplit.getItems().size() == 2) {
            workSplit.setDividerPositions(panels.state(Panel.FILE_TREE).divider());
        }
        if (contentSplit.getItems().size() == 2) {
            contentSplit.setDividerPositions(panels.state(Panel.TERMINAL).divider());
        }
    }

    private void openDetached(Panel panel, Region node) {
        if (detachedWindows.containsKey(panel)) {
            return;
        }
        StackPane holder = new StackPane(node);
        holder.getStyleClass().add("workbench-root");
        Scene scene = new Scene(holder, panel == Panel.TERMINAL ? 720 : 420, panel == Panel.TERMINAL ? 320 : 560);
        if (stage.getScene() != null) {
            scene.getStylesheets().setAll(stage.getScene().getStylesheets());
        }
        scene.setOnKeyPressed(this::onKeyPressed);
        Stage window = new Stage();
        window.initOwner(stage);
        window.setTitle(switch (panel) {
            case FILE_TREE -> "Çalışma Alanı";
            case RESULTS -> "Gezgin";
            case TERMINAL -> "Terminal";
        });
        window.setScene(scene);
        // Closing the window re-docks the panel rather than losing it.
        window.setOnCloseRequest(e -> withController(c -> c.panels().show(panel)));
        detachedWindows.put(panel, window);
        window.show();
    }

    private void closeDetached(Panel panel) {
        Stage window = detachedWindows.remove(panel);
        if (window == null) {
            return;
        }
        window.setOnCloseRequest(null);
        if (window.getScene().getRoot() instanceof StackPane holder) {
            holder.getChildren().clear(); // release the panel node before it is docked elsewhere
        }
        window.close();
    }

    // ================================================================== focus mode

    private void applyFocus(FocusState previous, FocusState current) {
        applyFocusLayout(current);
        commandBar.setChromeVisible(current.visible(Element.TOOLBAR));
        results.setBadgesVisible(current.visible(Element.BADGES));
        updateModeChrome();
        if (previous == null) {
            return;
        }
        if (!current.visible(Element.PREVIEW_PANE) && previous.visible(Element.PREVIEW_PANE)) {
            closePreview();
        }
        if (previous.focus() != current.focus()) {
            FadeTransition fade = new FadeTransition(Duration.millis(160), center.getChildren().getFirst());
            fade.setFromValue(0.55);
            fade.setToValue(1);
            fade.play();
            retainFocus();
            status(current.focus() ? "Odak modu" : "Odak modundan çıkıldı");
        }
        FocusModeCoordinator f = focus;
        if (f != null && previous.quiet() && !current.quiet()) {
            long suppressed = f.drainSuppressedNotifications();
            if (suppressed > 0) {
                toasts.post(Severity.INFO, suppressed + " bildirim sessiz modda bastırıldı");
            }
        }
    }

    /**
     * Mirrors the focus state onto the chrome this class owns: the bottom status bar and the browser's "Hızlı erişim"
     * card. Both stay where they were built and only give up their space ({@code visible} and {@code managed}
     * together), which leaves the call idempotent and free to repeat — focus events also fire for quiet mode and
     * telemetry, where the layout must not move at all.
     *
     * <p>Nothing is reparented, so a toggle costs one layout pass and no node ever loses its state: the search field
     * keeps focus, caret and selection, and the tables keep their column widths. The sidebar and the terminal are
     * deliberately not touched here — {@link FocusModeCoordinator} hides and restores them through the
     * {@link PanelStateCoordinator}, which removes their nodes from the split panes (a {@code SplitPane} item that is
     * merely invisible still holds its divider) and re-docks them at the remembered positions.</p>
     */
    private void applyFocusLayout(FocusState state) {
        boolean status = state.visible(Element.STATUS_BAR);
        statusBar.setVisible(status);
        statusBar.setManaged(status);
        browser.setQuickAccessVisible(!state.focus());
    }

    /**
     * Keeps the keyboard somewhere useful across a focus toggle. Since the toggle reparents nothing, a user who was
     * typing simply keeps the caret where it was; the only owner that can be lost is one that left the scene with its
     * panel — a tree cell or the terminal — and the search input is the control focus mode guarantees is on screen.
     * Runs after the panels have been re-docked, so on the way out the restored tree can take focus back itself.
     */
    private void retainFocus() {
        Scene scene = shell.getScene();
        if (scene == null) {
            return;
        }
        Node owner = scene.getFocusOwner();
        if (owner == null || owner.getScene() != scene) {
            commandBar.focusInput();
        }
    }

    /** Mode label plus the live heap badge, which exists (and ticks) only in detailed mode outside focus mode. */
    private void updateModeChrome() {
        WorkbenchController c = controller;
        FocusModeCoordinator f = focus;
        ViewMode mode = c == null ? ViewMode.SIMPLE : c.view().mode();
        statusMode.setText(mode == ViewMode.DETAILED ? "IT / DETAYLI" : "BASİT");
        boolean telemetry = mode == ViewMode.DETAILED && (f == null || f.isVisible(Element.TELEMETRY));
        boolean mounted = statusBar.getChildren().contains(statusHeap);
        if (telemetry && !mounted) {
            statusBar.getChildren().add(statusBar.getChildren().indexOf(statusMode) + 1, statusHeap);
            updateHeap();
            heapTicker.play();
        } else if (!telemetry && mounted) {
            heapTicker.stop();
            statusBar.getChildren().remove(statusHeap);
        }
        syncToggles();
    }

    private void updateHeap() {
        MemorySnapshot m = MemorySnapshot.now();
        statusHeap.setText(String.format(Locale.ROOT, "heap %d/%d MB (%d%%) · gc %d", m.usedBytes() >> 20,
                m.maxBytes() >> 20, m.usedPercent(), m.gcCount()));
    }

    // ================================================================== keyboard

    private void onKeyPressed(KeyEvent e) {
        WorkbenchController c = controller;
        if (c == null) {
            return;
        }
        if (e.getCode() == KeyCode.ESCAPE && quickLook.isShowing()) {
            closePreview();
            e.consume();
            return;
        }
        // Esc leaves focus mode, never enters it. It is the last claimant on the key: the command bar's own filter
        // has already consumed it if there was text to clear, and an open preview is handled just above.
        if (e.getCode() == KeyCode.ESCAPE && !e.isControlDown() && !e.isAltDown() && !e.isShiftDown()
                && !e.isMetaDown()) {
            FocusModeCoordinator f = focus;
            if (f != null && f.focusActive()) {
                perform(Action.TOGGLE_FOCUS); // same path as the switch and the menu item
                e.consume();
                return;
            }
        }
        if (e.getCode() == KeyCode.F1 && !e.isControlDown() && !e.isAltDown() && !e.isShiftDown() && !e.isMetaDown()) {
            showCommandGuide();
            e.consume();
            return;
        }
        KeyStroke stroke = toStroke(e);
        if (stroke == null) {
            return;
        }
        // While typing, only chorded strokes and function keys are shortcuts; Enter, arrows and letters belong to
        // the text field.
        boolean typing = e.getTarget() instanceof TextInputControl
                || (root.getScene() != null && root.getScene().getFocusOwner() instanceof TextInputControl);
        boolean chord = (stroke.modifiers() & (KeyStroke.CTRL | KeyStroke.ALT | KeyStroke.META)) != 0;
        if (typing && !chord && !e.getCode().isFunctionKey()) {
            return;
        }
        if (c.dispatch(stroke)) {
            e.consume();
        }
    }

    /**
     * Scene-level filter for the two explorer gestures the focused control would otherwise swallow:
     *
     * <ul>
     *   <li>a bare {@code Space} while a browser table, the result list or the open preview has focus toggles Quick
     *       Look (the selected document or result; closes the open preview). Everywhere else — the command bar, the
     *       navigation pane, buttons — Space keeps its normal meaning;</li>
     *   <li>{@code Alt+←/→/↑} go back, forward and up, as in a file manager (not while Quick Look is open).</li>
     * </ul>
     */
    private void onSpace(KeyEvent e) {
        if (controller == null) {
            return;
        }
        if (e.isAltDown() && !e.isControlDown() && !e.isShiftDown() && !e.isMetaDown() && !quickLook.isShowing()) {
            switch (e.getCode()) {
                case LEFT -> browser.back();
                case RIGHT -> browser.forward();
                case UP -> browser.up();
                default -> {
                    return;
                }
            }
            e.consume();
            return;
        }
        if (e.getCode() != KeyCode.SPACE || e.isControlDown() || e.isAltDown() || e.isShiftDown() || e.isMetaDown()) {
            return;
        }
        Node owner = shell.getScene() == null ? null : shell.getScene().getFocusOwner();
        if (owner == null || owner instanceof TextInputControl || owner instanceof ButtonBase) {
            return;
        }
        if (isWithin(owner, quickLook) || isWithin(owner, results)) {
            togglePreview();
        } else if (browser.isInTable(owner)) {
            if (quickLook.isShowing()) {
                closePreview();
            } else {
                browser.selectedDocument().ifPresentOrElse(this::previewDocument,
                        () -> browser.selectedAsset().ifPresent(this::openAsset)); // media: the OS app previews
            }
        } else {
            return;
        }
        e.consume();
    }

    private static boolean isWithin(Node node, Node ancestor) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == ancestor) {
                return true;
            }
        }
        return false;
    }

    /** JavaFX key event → registry stroke, or {@code null} for keys the registry cannot bind. */
    static KeyStroke toStroke(KeyEvent e) {
        KeyCode code = e.getCode();
        if (code == null || code == KeyCode.UNDEFINED || code.isModifierKey()) {
            return null;
        }
        String key = code.isDigitKey() && code.getName().length() == 1 ? code.getName() : code.name();
        int modifiers = (e.isControlDown() ? KeyStroke.CTRL : 0) | (e.isAltDown() ? KeyStroke.ALT : 0)
                | (e.isShiftDown() ? KeyStroke.SHIFT : 0) | (e.isMetaDown() ? KeyStroke.META : 0);
        try {
            return KeyStroke.of(modifiers, key);
        } catch (IllegalArgumentException unknownKey) {
            return null;
        }
    }

    /** Re-labels every surface that shows a shortcut; runs on each registry change (set-key, reload, reset). */
    private void applyBindings() {
        WorkbenchController c = controller;
        if (c == null) {
            return;
        }
        KeyMapRegistry keys = c.keys();
        results.setPreviewShortcut(stroke(keys, Action.PREVIEW_SELECTED));
        results.setOpenShortcut(stroke(keys, Action.OPEN_SELECTED));
        results.setRevealShortcut(stroke(keys, Action.SHOW_IN_EXPLORER));
        StringBuilder hint = new StringBuilder();
        appendHint(hint, keys, Action.FOCUS_SEARCH, "ara");
        appendHint(hint, keys, Action.TOGGLE_MODE, "mod");
        appendHint(hint, keys, Action.TOGGLE_FOCUS, "odak");
        appendHint(hint, keys, Action.TOGGLE_TERMINAL, "terminal");
        commandBar.setHint(hint.toString());
        tip(terminalToggle, "Terminali aç/kapat", stroke(keys, Action.TOGGLE_TERMINAL));
        tip(focusToggle, "Odak modu: yalnızca arama ve sonuçlar", stroke(keys, Action.TOGGLE_FOCUS));
        label(treeItem, "Gezinti bölmesi", stroke(keys, Action.TOGGLE_FILE_TREE));
        label(terminalItem, "Terminal", stroke(keys, Action.TOGGLE_TERMINAL));
        label(detailItem, "Detaylı (IT) görünüm", stroke(keys, Action.TOGGLE_MODE));
        label(focusItem, "Odak modu", stroke(keys, Action.TOGGLE_FOCUS));
        label(saveItem, "İndeksi Kaydet", stroke(keys, Action.SAVE_INDEX));
    }

    private static void tip(javafx.scene.control.Control control, String text, String stroke) {
        control.setTooltip(new Tooltip(stroke == null ? text : text + "  (" + stroke + ")"));
    }

    /** Menu items show their current stroke as a label only (a real accelerator would bypass the registry). */
    private static void label(MenuItem item, String text, String stroke) {
        item.setText(stroke == null ? text : text + "    " + stroke);
    }

    private static String stroke(KeyMapRegistry keys, Action action) {
        return keys.strokeFor(action).map(Object::toString).orElse(null);
    }

    private static void appendHint(StringBuilder sb, KeyMapRegistry keys, Action action, String label) {
        String s = stroke(keys, action);
        if (s != null) {
            sb.append(sb.isEmpty() ? "" : "   ").append(s).append(' ').append(label);
        }
    }

    /** Picks up edits to {@code keybindings.properties} made outside the app when the window regains focus. */
    private void reloadKeysIfChanged() {
        WorkbenchController c = controller;
        if (c == null) {
            return;
        }
        Thread.ofVirtual().name("dwb-ui-keys").start(() -> {
            try {
                c.keys().reloadIfChanged().ifPresent(issues -> {
                    InternalTerminalEngine.Output out = c.terminal().output();
                    out.println("keys: " + c.keys().file().getFileName() + " reloaded");
                    issues.forEach(i -> out.println("keys: " + i));
                });
            } catch (IOException e) {
                c.terminal().output().error("keys: reload failed: " + e.getMessage());
            }
        });
    }

    // ================================================================== command bar

    private void submit(Mode mode, String line) {
        WorkbenchController c = controller;
        if (c == null) {
            return;
        }
        if (mode == Mode.COMMAND) {
            runCommand(c, line.strip().substring(1).strip());
            return;
        }
        long generation = queryGeneration.incrementAndGet();
        browser.navigate(new WorkspaceBrowserView.Results(mode, line.strip()));
        long token;
        if (mode == Mode.SEARCH) {
            results.clearAnswer();
            token = -1;
        } else {
            token = results.beginAnswer(mode == Mode.WEB ? "WEB · " + WebSearchBridge.stripPrefix(line)
                    : "YANIT · " + line.substring(1).strip());
        }
        status(switch (mode) {
            case ASK -> "Kanıt aranıyor ve yanıt oluşturuluyor…";
            case WEB -> "Web araması (ölçümlü)…";
            default -> "Aranıyor…";
        });
        TokenSink sink = delta -> {
            if (generation != queryGeneration.get()) {
                // A newer query (or a project switch) replaced this one: its tokens can no longer be shown, so the
                // stream is aborted instead of being paid for in the background.
                throw new java.util.concurrent.CancellationException("superseded by a newer query");
            }
            results.appendAnswer(token, delta);
        };
        Thread.ofVirtual().name("dwb-ui-query").start(() -> {
            try {
                QueryOutcome outcome = ext.submitQuery(line, sink);
                Fx.run(() -> {
                    if (generation == queryGeneration.get()) {
                        present(outcome, token);
                    }
                });
            } catch (AnswerException e) {
                if (generation != queryGeneration.get()) {
                    return; // cancelled because it was superseded; nothing to report
                }
                results.finishAnswer(token, "Yanıt alınamadı: " + e.getMessage(), true, List.of());
                toasts.post(Severity.WARNING, "Yanıt alınamadı (" + e.kind() + ")");
                Fx.run(() -> status("Yanıt alınamadı"));
            } catch (RuntimeException e) {
                if (generation != queryGeneration.get()) {
                    return;
                }
                if (token >= 0) {
                    results.finishAnswer(token, "Hata: " + e.getMessage(), true, List.of());
                }
                toasts.post(Severity.WARNING, "Sorgu başarısız: " + e.getMessage());
                Fx.run(() -> status("Sorgu başarısız"));
            }
        });
    }

    private void present(QueryOutcome outcome, long token) {
        switch (outcome) {
            case QueryOutcome.Local(SearchResult r) -> status(String.format(Locale.ROOT, "%d sonuç · %.2f ms",
                    r.hits().size(), r.elapsedMillis()));
            case QueryOutcome.Answer(Workbench.AskOutcome a) -> {
                List<SourceChip> chips = new ArrayList<>(a.sources().size());
                for (Workbench.Source s : a.sources()) {
                    chips.add(new SourceChip(s.tag(), s.fileName(), coordinate(s.page(), s.chunkIndex()), s.docId()));
                }
                String notice = switch (a.outcome()) {
                    case ANSWERED -> null;
                    case NO_MATCH -> "Belgelerde eşleşme bulunamadı; model çağrılmadı.";
                    case BELOW_THRESHOLD -> String.format(Locale.ROOT,
                            "En iyi kanıt eşiğin altında (skor %.2f); model çağrılmadı.", a.topScore());
                };
                results.finishAnswer(token, notice, false, chips);
                status(a.answered() && a.stats() != null
                        ? String.format(Locale.ROOT, "%s · %d kaynak · %d/%d token", a.stats().model(),
                        a.sources().size(), a.stats().promptTokens(), a.stats().outputTokens())
                        : "Yanıt yok: " + a.outcome());
            }
            case QueryOutcome.Web(HybridOutcome h) -> presentWeb(h, token);
        }
    }

    private void presentWeb(HybridOutcome outcome, long token) {
        switch (outcome) {
            case HybridOutcome.Answered a -> {
                List<SourceChip> chips = new ArrayList<>(a.sources().size());
                for (WebSearchBridge.Source s : a.sources()) {
                    chips.add(new SourceChip(s.tag(), s.title(), s.location(), null));
                }
                results.finishAnswer(token, null, false, chips);
                status(String.format(Locale.ROOT, "web: %d/%d parça tutuldu (%.0f%% elendi) · %d/%d token",
                        a.distillation().kept(), a.distillation().fetched(), a.distillation().reduction() * 100,
                        a.stats().promptTokens(), a.stats().outputTokens()));
            }
            case HybridOutcome.SnippetsOnly s -> {
                StringBuilder text = new StringBuilder();
                for (WebSearchBridge.WebSnippet w : s.snippets()) {
                    text.append(w.rank()).append(". ").append(w.title()).append('\n')
                            .append(w.text()).append('\n').append(w.url()).append("\n\n");
                }
                results.appendAnswer(token, text.toString());
                results.finishAnswer(token, "Yapay zekâ modeli yapılandırılmamış; ilgili web parçaları gösteriliyor.",
                        false, List.of());
                status("web: " + s.snippets().size() + " parça");
            }
            case HybridOutcome.LocalOnly l -> {
                results.finishAnswer(token, "Web/yapay zekâ kullanılmadı (" + l.reason()
                        + (l.detail().isBlank() ? "" : ": " + l.detail()) + ") — yerel sonuçlar listelendi.",
                        false, List.of());
                status("web kullanılmadı: " + l.reason());
            }
        }
    }

    private static String coordinate(int page, int chunkIndex) {
        return (page > 0 ? "p." + page + " " : "") + "#" + chunkIndex;
    }

    /** {@code > line} from the command bar: runs in the terminal, which is docked unless focus mode is on. */
    private CompletableFuture<InternalTerminalEngine.Outcome> runCommand(WorkbenchController c, String line) {
        if (line.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        FocusModeCoordinator f = focus;
        if (f == null || !f.focusActive()) {
            c.panels().show(Panel.TERMINAL);
        }
        return c.terminal().submit(line).whenComplete((outcome, error) -> {
            switch (outcome) {
                case InternalTerminalEngine.Outcome.Failed failed ->
                        toasts.post(Severity.WARNING, "Komut başarısız: " + failed.message());
                case InternalTerminalEngine.Outcome.Unknown unknown ->
                        toasts.post(Severity.WARNING, "Bilinmeyen komut '" + unknown.name() + "'"
                                + (unknown.suggestion() == null ? "" : " — " + unknown.suggestion() + "?"));
                case null -> {
                }
                default -> {
                }
            }
        });
    }

    // ================================================================== OS actions

    private void open(int row) {
        withController(c -> {
            c.select(row);
            c.openRow(row).thenAccept(this::reportUnaudited);
        });
    }

    private void reveal(int row) {
        withController(c -> {
            c.select(row);
            c.revealRow(row).thenAccept(this::reportUnaudited);
        });
    }

    /**
     * Launch failures arrive as {@link WorkbenchEvent.FileAction} events (and are toasted there); only a request
     * refused before reaching the shell — no row, no path — needs reporting here.
     */
    private void reportUnaudited(ShellResult result) {
        if (!result.ok() && result.path() == null) {
            toasts.post(Severity.INFO, result.describe());
        }
    }

    private void onWorkbenchEvent(WorkbenchEvent event) {
        switch (event) {
            case WorkbenchEvent.Ingested i -> treeRefresh.request();
            case WorkbenchEvent.AssetRegistered a -> treeRefresh.request();
            case WorkbenchEvent.IngestFailed f -> {
                if (bulkIngests.get() == 0) {
                    toasts.post(Severity.WARNING, name(f.file()) + ": " + f.message());
                }
            }
            case WorkbenchEvent.IngestRefused r -> {
                if (bulkIngests.get() == 0) {
                    toasts.post(Severity.WARNING, name(r.file()) + " reddedildi: " + r.reason());
                }
            }
            case WorkbenchEvent.FileAction a -> {
                if (!a.result().ok()) {
                    toasts.post(Severity.WARNING, a.result().describe());
                }
            }
            case WorkbenchEvent.CommandFailed f -> {
                WorkbenchController c = controller;
                if (c != null && !c.panels().isShown(Panel.TERMINAL)) {
                    toasts.post(Severity.WARNING, "Komut başarısız: " + f.message());
                }
            }
            case WorkbenchEvent.Removed r -> treeRefresh.request();
        }
    }

    private static String name(Path path) {
        return path == null || path.getFileName() == null ? String.valueOf(path) : path.getFileName().toString();
    }

    // ================================================================== ingestion & file tree

    private void ingest(List<Path> paths) {
        WorkbenchController c = controller;
        if (c == null || paths.isEmpty()) {
            return;
        }
        fileTree.setBusy("İçe aktarılıyor: " + paths.size() + " öğe…");
        bulkIngests.incrementAndGet();
        Thread.ofVirtual().name("dwb-ui-ingest").start(() -> {
            AtomicInteger failed = new AtomicInteger();
            // Collected here rather than toasted per file: during a bulk import the per-file handler in
            // onWorkbenchEvent stays silent, and this summary is the one notification the user gets.
            List<String> reasons = new ArrayList<>();
            Subscription counting = c.onEvent(e -> {
                switch (e) {
                    case WorkbenchEvent.IngestFailed f -> {
                        failed.incrementAndGet();
                        addReason(reasons, name(f.file()) + ": " + f.message());
                    }
                    case WorkbenchEvent.IngestRefused r -> {
                        failed.incrementAndGet();
                        addReason(reasons, name(r.file()) + " reddedildi: " + r.reason());
                    }
                    default -> {
                    }
                }
            });
            int added = 0;
            InternalTerminalEngine.Output out = c.terminal().output();
            try {
                for (Path p : paths) {
                    try {
                        added += c.addPath(p, out);
                    } catch (IOException | RuntimeException e) {
                        failed.incrementAndGet();
                        out.println("  ! " + p + ": " + e.getMessage());
                    }
                }
            } finally {
                counting.close();
                bulkIngests.decrementAndGet();
            }
            int total = added;
            Fx.run(() -> {
                fileTree.setBusy(null);
                refreshTree();
            });
            // USER_ACTION: the user started this import by dropping files, pressing the shortcut or typing the
            // command, so its result reaches them even in focus mode, where the terminal is not on screen.
            toasts.post(failed.get() > 0 ? Severity.WARNING : Severity.INFO,
                    importSummary(total, failed.get(), reasons), Origin.USER_ACTION);
        });
    }

    private static void addReason(List<String> reasons, String reason) {
        synchronized (reasons) {
            if (reasons.size() < MAX_REPORTED_REASONS) {
                reasons.add(reason);
            }
        }
    }

    /**
     * The single notification an import produces. It spells the refusals out instead of pointing at the terminal,
     * because in focus mode the terminal is not on screen — but only the first {@value #MAX_REPORTED_REASONS}, so a
     * folder full of rejected files cannot turn one import into a wall of text.
     */
    private static String importSummary(int added, int failed, List<String> reasons) {
        StringBuilder text = new StringBuilder(added + " belge eklendi");
        if (failed == 0) {
            return text.toString();
        }
        text.append(", ").append(failed).append(" dosya alınamadı");
        synchronized (reasons) {
            for (String reason : reasons) {
                text.append("\n· ").append(reason);
            }
            if (failed > reasons.size()) {
                text.append("\n· … ").append(failed - reasons.size()).append(" tane daha (ayrıntı terminalde)");
            }
        }
        return text.toString();
    }

    /** Rebuilds the folder model from the index and hands it to the browser and the navigation pane (FX thread). */
    private void refreshTree() {
        WorkbenchController c = controller;
        if (c == null) {
            return;
        }
        List<DocumentRecord> documents = c.workbench().documents();
        List<Path> watchRoots = ext.projects().active().map(a -> a.profile().watchRoots()).orElse(List.of());
        FolderIndex index = FolderIndex.build(documents, c.assets(), watchRoots);
        Map<Path, String> titles = Map.copyOf(folderTitles);
        List<FileTreePanelView.Root> roots = new ArrayList<>(index.roots().size());
        for (FolderIndex.Folder f : index.roots()) {
            roots.add(new FileTreePanelView.Root(f.path(), titles.getOrDefault(f.path(), f.name()),
                    f.documents() + f.assets()));
        }
        fileTree.setRoots(roots, index.documentCount(), index.chunkCount());
        browser.setIndex(index, titles); // fires placeChanged, which refreshes crumbs and the item count
    }

    // ================================================================== navigation

    /** Mirrors the browser's location into the breadcrumbs, the navigation buttons, the pane and the status bar. */
    private void placeChanged() {
        Place place = browser.place();
        List<CommandBarView.Crumb> crumbs = new ArrayList<>();
        String project = ext.projects().active().map(a -> a.profile().name()).orElse("—");
        crumbs.add(new CommandBarView.Crumb("⌂  Giriş", "Giriş panosu · " + project,
                () -> browser.navigate(WorkspaceBrowserView.HOME)));
        switch (place) {
            case WorkspaceBrowserView.Home h -> {
            }
            case WorkspaceBrowserView.InFolder(Path p) -> {
                for (FolderIndex.Folder f : browser.index().trail(p)) {
                    Path target = f.path();
                    crumbs.add(new CommandBarView.Crumb(browser.title(f), target.toString(),
                            () -> browser.navigate(new WorkspaceBrowserView.InFolder(target))));
                }
            }
            case WorkspaceBrowserView.Results(Mode mode, String line) -> crumbs.add(new CommandBarView.Crumb(
                    switch (mode) {
                        case ASK -> "Yanıt";
                        case WEB -> "Web araması";
                        default -> "Arama sonuçları";
                    } + ": “" + Mode.body(line) + "”", line, null));
        }
        commandBar.setCrumbs(crumbs);
        commandBar.setNavigationEnabled(browser.canGoBack(), browser.canGoForward(), browser.canGoUp());
        fileTree.setCurrent(browser.currentFolder());
        int items = browser.itemCount();
        statusDocs.setText(items < 0 ? "" : items + " öğe");
    }

    // ================================================================== preview

    /**
     * Opens the Quick Look preview for {@code row}. The size gate runs first on this thread (one {@code stat}): an
     * oversized file shows its warning at once and is never handed to a parser. The request still goes through
     * {@code PreviewService#previewAsync} — which applies the same gate before any extraction — so every attempt is
     * audited and the previous preview is released.
     */
    private void previewRow(int row) {
        WorkbenchController c = controller;
        if (c == null) {
            return;
        }
        Optional<Path> source = c.sourceOf(row);
        if (source.isEmpty()) {
            toasts.post(Severity.INFO, "Önizlenecek bir sonuç seçili değil");
            syncToggles();
            return;
        }
        previewPath(c, source.get());
    }

    /** Same size-gated flow for any path (file-tree documents have no result row). */
    private void previewPath(WorkbenchController c, Path path) {
        long request = ++previewRequest;
        Optional<PreviewResult> refused = ext.preview().preflight(path);
        if (refused.isPresent()) {
            previewPane.show(refused.get());
        } else {
            previewPane.showLoading(path);
        }
        quickLook.show();
        ext.preview().previewAsync(path).thenAccept(result -> Fx.run(() -> {
            if (request == previewRequest && refused.isEmpty() && !(result instanceof PreviewResult.Superseded)) {
                previewPane.show(result);
            }
        }));
        syncToggles();
    }

    private void togglePreview() {
        if (quickLook.isShowing()) {
            closePreview();
        } else {
            perform(Action.PREVIEW_SELECTED);
        }
        syncToggles();
    }

    /** Unmounts the Quick Look overlay and drops every reference to the previewed document right away. */
    private void closePreview() {
        previewRequest++;
        quickLook.hide();
        previewPane.clear();
        ext.preview().dismiss();
        syncToggles();
    }

    /** "Dosyayı Aç" on the preview card: the default application, through the audited shell bridge. */
    private void openPreviewed() {
        Path shown = previewPane.shownPath();
        if (shown != null) {
            withController(c -> c.openPath(shown).thenAccept(this::reportUnaudited));
        }
    }

    // ================================================================== document actions

    private void openDocument(DocumentRecord d) {
        withController(c -> c.openPath(d.source()).thenAccept(this::reportUnaudited));
    }

    private void revealDocument(DocumentRecord d) {
        withController(c -> c.revealPath(d.source()).thenAccept(this::reportUnaudited));
    }

    // ------------------------------------------------------------------ binary assets (video, image, audio, archive)

    private void openAsset(BinaryAsset a) {
        withController(c -> c.openPath(a.source()).thenAccept(this::reportUnaudited));
    }

    private void revealAsset(BinaryAsset a) {
        withController(c -> c.revealPath(a.source()).thenAccept(this::reportUnaudited));
    }

    private void copyAssetPath(BinaryAsset a) {
        withController(c -> {
            String path = c.copyPath(a, clipboard);
            toasts.post(Severity.INFO, "Yol kopyalandı: " + path);
        });
    }

    private void untrackAsset(BinaryAsset a) {
        withController(c -> {
            if (c.removeAsset(a.sha256())) {
                toasts.post(Severity.INFO, a.fileName() + " listeden kaldırıldı (dosya diskte duruyor)");
            }
            treeRefresh.request();
        });
    }

    private void previewDocument(DocumentRecord d) {
        withController(c -> previewPath(c, d.source()));
    }

    private void copyPath(DocumentRecord d) {
        withController(c -> {
            String path = c.copyPath(d, clipboard);
            toasts.post(Severity.INFO, "Yol kopyalandı: " + path);
        });
    }

    private void copyContent(DocumentRecord d) {
        withController(c -> {
            int chars = c.copyContent(d, clipboard);
            long total = d.charCount();
            toasts.post(Severity.INFO, String.format(Locale.ROOT, "%s: %,d karakter kopyalandı%s", d.fileName(),
                    chars, total > chars ? " (ilk " + chars + " / " + total + ")" : ""));
        });
    }

    // ================================================================== folder actions

    /** Opens the folder itself in the system file manager (audited like every shell action). */
    private void revealFolder(Path folder) {
        withController(c -> c.openPath(folder).thenAccept(this::reportUnaudited));
    }

    private void copyFolderPath(Path folder) {
        String path = folder.toAbsolutePath().toString();
        clipboard.putText(path);
        toasts.post(Severity.INFO, "Yol kopyalandı: " + path);
    }

    /** Custom card title for {@code folder}; an empty name restores the folder's own name. */
    private void renameFolder(Path folder) {
        String current = folderTitles.getOrDefault(folder, FolderIndex.displayName(folder));
        TextInputDialog dialog = styled(new TextInputDialog(current));
        dialog.setTitle("Yeniden Adlandır");
        dialog.setHeaderText("Kart başlığı: " + folder + "\nYalnızca uygulamadaki görünen ad değişir; diskteki klasör"
                + " adı değişmez. Boş bırakırsanız klasör adı kullanılır.");
        dialog.setContentText("Başlık:");
        Optional<String> answer = dialog.showAndWait().map(String::strip);
        if (answer.isEmpty()) {
            return;
        }
        String title = answer.get();
        if (title.isEmpty() || title.equals(FolderIndex.displayName(folder))) {
            folderTitles.remove(folder);
        } else {
            folderTitles.put(folder, title);
        }
        saveFolderTitles();
        refreshTree();
    }

    /** Index-only removal: nothing on disk changes, so no confirmation (the file can simply be added again). */
    private void untrack(DocumentRecord d) {
        // Off the FX thread: a removal can trigger an index compaction that re-tokenizes every document.
        withController(c -> Thread.ofVirtual().name("dwb-ui-untrack")
                .start(() -> afterRemoval(c, c.untrack(d.sha256()))));
    }

    /** Disk deletion: cheap checks, a confirmation dialog, then hashing + delete on a virtual thread. */
    private void purge(DocumentRecord d) {
        WorkbenchController c = controller;
        if (c == null) {
            return;
        }
        Optional<String> refusal = c.purgeRefusal(d);
        if (refusal.isPresent()) {
            toasts.post(Severity.WARNING, "Silinemez: " + refusal.get());
            return;
        }
        if (!confirmPurge(d)) {
            return;
        }
        status("Siliniyor: " + d.fileName() + "…");
        Thread.ofVirtual().name("dwb-ui-purge").start(() -> afterRemoval(c, c.purge(d.sha256())));
    }

    private boolean confirmPurge(DocumentRecord d) {
        Alert alert = styled(new Alert(Alert.AlertType.WARNING));
        alert.setTitle("Diskten Tamamen Sil");
        alert.setHeaderText("“" + d.fileName() + "” kalıcı olarak silinsin mi?");
        alert.setContentText(d.source().toAbsolutePath() + "\n\nDosya diskten silinir ve indeksten kaldırılır."
                + " Bu işlem geri alınamaz; dosya Geri Dönüşüm Kutusu'na gönderilmez.");
        ButtonType delete = new ButtonType("Diskten Sil", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Vazgeç", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(cancel, delete);
        Button deleteButton = (Button) alert.getDialogPane().lookupButton(delete);
        deleteButton.getStyleClass().add("danger-button");
        deleteButton.setDefaultButton(false);
        ((Button) alert.getDialogPane().lookupButton(cancel)).setDefaultButton(true); // Enter never deletes
        return alert.showAndWait().filter(b -> b == delete).isPresent();
    }

    /** Index-only removal of a multi-selection: one background job, one summary, one save. */
    private void untrackAll(List<DocumentRecord> documents) {
        List<String> hashes = documents.stream().map(DocumentRecord::sha256).toList(); // copied on the FX thread
        withController(c -> Thread.ofVirtual().name("dwb-ui-untrack")
                .start(() -> afterRemoval(c, c.untrackAll(hashes))));
    }

    /**
     * Disk deletion of a multi-selection: the cheap checks sort out what may be deleted, one confirmation covers
     * the rest, and each file is then verified and deleted exactly like a single {@link #purge}.
     */
    private void purgeAll(List<DocumentRecord> documents) {
        WorkbenchController c = controller;
        if (c == null) {
            return;
        }
        List<DocumentRecord> deletable = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        for (DocumentRecord d : documents) {
            Optional<String> refusal = c.purgeRefusal(d);
            if (refusal.isPresent()) {
                refused.add(d.fileName() + ": " + refusal.get());
            } else {
                deletable.add(d);
            }
        }
        if (deletable.isEmpty()) {
            toasts.post(Severity.WARNING, "Silinemez: " + String.join("; ", refused.subList(0,
                    Math.min(refused.size(), MAX_REPORTED_REASONS))));
            return;
        }
        if (!confirmPurgeAll(deletable, refused.size())) {
            return;
        }
        status("Siliniyor: " + deletable.size() + " belge…");
        List<String> hashes = deletable.stream().map(DocumentRecord::sha256).toList();
        Thread.ofVirtual().name("dwb-ui-purge").start(() -> {
            List<RemovalResult> results = new ArrayList<>();
            for (String sha : hashes) {
                results.add(c.purge(sha));
            }
            afterRemoval(c, results);
        });
    }

    private boolean confirmPurgeAll(List<DocumentRecord> documents, int skipped) {
        Alert alert = styled(new Alert(Alert.AlertType.WARNING));
        alert.setTitle("Diskten Tamamen Sil");
        alert.setHeaderText(documents.size() + " belge kalıcı olarak silinsin mi?");
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < documents.size() && i < 8; i++) {
            list.append("• ").append(documents.get(i).fileName()).append('\n');
        }
        if (documents.size() > 8) {
            list.append("… ve ").append(documents.size() - 8).append(" belge daha\n");
        }
        alert.setContentText(list + "\nDosyalar diskten silinir ve indeksten kaldırılır. Bu işlem geri alınamaz;"
                + " dosyalar Geri Dönüşüm Kutusu'na gönderilmez."
                + (skipped > 0 ? "\n\n" + skipped + " belge silinemeyeceği için atlandı." : ""));
        ButtonType delete = new ButtonType("Diskten Sil", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Vazgeç", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(cancel, delete);
        Button deleteButton = (Button) alert.getDialogPane().lookupButton(delete);
        deleteButton.getStyleClass().add("danger-button");
        deleteButton.setDefaultButton(false);
        ((Button) alert.getDialogPane().lookupButton(cancel)).setDefaultButton(true); // Enter never deletes
        return alert.showAndWait().filter(b -> b == delete).isPresent();
    }

    /** Batch variant: one toast and one status line for the whole selection, then a single save and re-query. */
    private void afterRemoval(WorkbenchController c, List<RemovalResult> results) {
        if (results.size() == 1) {
            afterRemoval(c, results.getFirst());
            return;
        }
        long ok = results.stream().filter(RemovalResult::ok).count();
        boolean fromDisk = results.stream().anyMatch(r -> r instanceof RemovalResult.Purged);
        StringBuilder text = new StringBuilder(ok + " belge " + (fromDisk ? "diskten silindi" : "indeksten kaldırıldı"));
        List<String> problems = results.stream().filter(r -> !r.ok()).map(RemovalResult::describe).toList();
        if (!problems.isEmpty()) {
            text.append(" · ").append(problems.size()).append(" başarısız: ")
                    .append(String.join("; ", problems.subList(0, Math.min(problems.size(), MAX_REPORTED_REASONS))));
        }
        String summary = text.toString();
        toasts.post(problems.isEmpty() ? Severity.INFO : Severity.WARNING, summary);
        Set<Path> purged = new HashSet<>();
        for (RemovalResult r : results) {
            if (r instanceof RemovalResult.Purged p) {
                purged.add(p.file());
            }
        }
        Fx.run(() -> {
            status(summary);
            if (previewPane.shownPath() != null && purged.contains(previewPane.shownPath())) {
                closePreview();
            }
            refreshTree();
        });
        if (ok > 0) {
            c.saveAsync();
            rerunQuery(c);
        }
    }

    /** Reports a removal, persists it and refreshes every view that may still show the document. */
    private void afterRemoval(WorkbenchController c, RemovalResult result) {
        toasts.post(result.ok() ? Severity.INFO : Severity.WARNING, result.describe());
        Fx.run(() -> {
            status(result.describe());
            if (result instanceof RemovalResult.Purged p && p.file().equals(previewPane.shownPath())) {
                closePreview();
            }
            refreshTree();
        });
        if (result.ok()) {
            c.saveAsync();
            rerunQuery(c);
        }
    }

    /** Re-runs the last BM25 query so removed documents leave the result list. */
    private void rerunQuery(WorkbenchController c) {
        String query = c.view().query();
        if (query == null || query.isBlank()) {
            return;
        }
        Thread.ofVirtual().name("dwb-ui-requery").start(() -> {
            try {
                c.search(query, 20);
            } catch (RuntimeException e) {
                status("Arama yenilenemedi: " + e.getMessage());
            }
        });
    }

    private Optional<DocumentRecord> rowDocument(int row) {
        WorkbenchController c = controller;
        Optional<DocumentRecord> d = c == null ? Optional.empty() : c.documentAt(row);
        if (d.isEmpty()) {
            toasts.post(Severity.INFO, "Sonuç artık indekste değil");
        }
        return d;
    }

    // ================================================================== toolbar actions

    private void addFiles() {
        fileTree.chooseFiles();
    }

    /**
     * {@code > add [path]} (alias {@code > ingest}): the command-bar route into the same pipeline as the toolbar and
     * drag & drop. Without an argument it opens the native chooser, which is what makes it useful in focus mode —
     * there is no "+ Yeni" button there. With an argument it takes the path straight; a folder is walked exactly as
     * when one is dropped. Runs on the terminal thread, so both branches hop to the FX thread.
     */
    private void cmdAdd(InternalTerminalEngine.Invocation invocation, InternalTerminalEngine.Output out) {
        String raw = unquote(invocation.joinedArgs().strip());
        if (raw.isEmpty()) {
            Fx.run(this::addFiles);
            out.println("Dosya seçici açıldı.");
            return;
        }
        Path path;
        try {
            path = Path.of(raw).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Geçersiz yol: " + raw);
        }
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Bulunamadı: " + path);
        }
        Fx.run(() -> ingest(List.of(path)));
        out.println("İçe aktarılıyor: " + path);
    }

    /** Strips one pair of surrounding quotes, so a pasted Windows path with spaces can be quoted. */
    private static String unquote(String value) {
        if (value.length() > 1 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1).strip();
        }
        return value;
    }

    private void refreshAll() {
        refreshTree();
        withController(this::rerunQuery);
        status("Yenilendi");
    }

    private void saveIndex() {
        withController(c -> {
            status("İndeks kaydediliyor…");
            c.saveAsync().whenComplete((report, error) -> {
                if (error != null) {
                    toasts.post(Severity.WARNING, "İndeks kaydedilemedi: " + rootMessage(error));
                    status("Kaydedilemedi");
                } else {
                    String text = String.format(Locale.ROOT, "İndeks kaydedildi: %d belge, %d KB (%.0f ms)",
                            report.documents(), report.bytes() >> 10, report.millis());
                    toasts.post(Severity.INFO, text);
                    status(text);
                }
            });
        });
    }

    private static String rootMessage(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return String.valueOf(t.getMessage());
    }

    // ================================================================== projects

    private void rebuildProjectMenu() {
        String activeId = ext.projects().active().map(a -> a.profile().id()).orElse("");
        List<MenuItem> items = new ArrayList<>();
        for (ProjectProfile p : ext.projects().list()) {
            boolean active = p.id().equals(activeId);
            MenuItem item = new MenuItem((active ? "✓  " : "     ") + p.name());
            item.setDisable(active);
            item.setOnAction(e -> switchProject(p));
            items.add(item);
        }
        MenuItem create = new MenuItem("+ Yeni Proje…");
        create.setOnAction(e -> newProject());
        MenuItem save = new MenuItem("Projeyi Kaydet");
        save.setOnAction(e -> saveIndex());
        items.add(new SeparatorMenuItem());
        items.add(create);
        items.add(save);
        projectMenu.getItems().setAll(items);
    }

    /** Saves the current project and activates {@code target}; the activation listener rebinds every view. */
    private void switchProject(ProjectProfile target) {
        status("'" + target.name() + "' projesine geçiliyor…");
        commandBar.setInputDisabled(true);
        ext.projects().switchAsync(target).whenComplete((report, error) -> {
            if (error != null) {
                toasts.post(Severity.CRITICAL, "Proje değiştirilemedi: " + rootMessage(error));
                Fx.run(() -> commandBar.setInputDisabled(controller == null));
                status("Proje değiştirilemedi");
            } else {
                status(report.describe());
            }
        });
    }

    private void newProject() {
        TextInputDialog dialog = styled(new TextInputDialog());
        dialog.setTitle("Yeni Proje");
        dialog.setHeaderText("Yalıtılmış yeni bir proje oluşturun.\nHer projenin kendi indeksi (.dwbproj) vardır.");
        dialog.setContentText("Proje adı:");
        Optional<String> name = dialog.showAndWait().map(String::strip).filter(n -> !n.isEmpty());
        if (name.isEmpty()) {
            return;
        }
        Thread.ofVirtual().name("dwb-ui-project").start(() -> {
            try {
                ProjectProfile created = ext.projects().create(name.get(), null);
                ext.admin().audit().record(AuditLog.Level.INFO, AuditLog.Category.PROJECT, "op", "create",
                        "project", created.name(), "root", created.root().toString());
                toasts.post(Severity.INFO, "Proje oluşturuldu: " + created.name());
                Fx.run(() -> switchProject(created));
            } catch (IOException | RuntimeException e) {
                toasts.post(Severity.WARNING, "Proje oluşturulamadı: " + e.getMessage());
            }
        });
    }

    // ================================================================== dialogs

    /** Owner, stylesheet and theme for every dialog, so none of them flashes the default Modena look. */
    private <D extends Dialog<?>> D styled(D dialog) {
        dialog.initOwner(stage);
        if (stage.getScene() != null) {
            dialog.getDialogPane().getStylesheets().setAll(stage.getScene().getStylesheets());
        }
        dialog.getDialogPane().getStyleClass().add("dwb-dialog");
        return dialog;
    }

    /**
     * Shortcut manager: every action with its current stroke, recorded by pressing the keys in the field. Changes go
     * through {@link KeyMapRegistry#bind} (conflicts reported, never silently overwritten) and are written to the
     * project's {@code keybindings.properties}, which is also hot-reloaded when edited outside the app.
     */
    private void showShortcutManager() {
        WorkbenchController c = controller;
        if (c == null) {
            return;
        }
        KeyMapRegistry keys = c.keys();
        Dialog<ButtonType> dialog = styled(new Dialog<>());
        dialog.setTitle("Klavye Kısayolları");
        dialog.setHeaderText("Bir alana tıklayın ve yeni tuş birleşimine basın.\nBackspace kısayolu kaldırır.");
        GridPane grid = new GridPane();
        grid.getStyleClass().add("shortcut-grid");
        grid.setHgap(12);
        grid.setVgap(6);
        EnumMap<Action, TextField> fields = new EnumMap<>(Action.class);
        int r = 0;
        for (Action a : Action.values()) {
            Label description = new Label(describe(a));
            description.getStyleClass().add("shortcut-description");
            TextField field = new TextField(Objects.requireNonNullElse(stroke(keys, a), ""));
            field.getStyleClass().add("shortcut-field");
            field.setEditable(false);
            field.setPromptText("(atanmamış)");
            field.setPrefColumnCount(12);
            field.addEventFilter(KeyEvent.KEY_PRESSED, e -> captureStroke(field, e));
            Label fallback = new Label("varsayılan " + a.defaultStroke());
            fallback.getStyleClass().add("shortcut-default");
            grid.addRow(r++, description, field, fallback);
            fields.put(a, field);
        }
        Label fixed = new Label("Sabit: Delete indeksten kaldırır · Shift+Delete diskten siler · Ctrl+C yolu,"
                + " Ctrl+Shift+C içeriği kopyalar · Alt+↓ arama modu · Space önizler · Esc önizlemeyi kapatır"
                + " · F1 komut rehberi");
        fixed.getStyleClass().add("shortcut-default");
        fixed.setWrapText(true);
        Label file = new Label("Dosya: " + keys.file());
        file.getStyleClass().add("shortcut-default");
        dialog.getDialogPane().setContent(new VBox(12, grid, fixed, file));

        ButtonType save = new ButtonType("Kaydet", ButtonBar.ButtonData.OK_DONE);
        ButtonType defaults = new ButtonType("Varsayılanlar", ButtonBar.ButtonData.LEFT);
        ButtonType openFile = new ButtonType("Dosyayı Aç", ButtonBar.ButtonData.LEFT);
        dialog.getDialogPane().getButtonTypes().setAll(defaults, openFile, ButtonType.CANCEL, save);
        dialog.getDialogPane().lookupButton(defaults).addEventFilter(ActionEvent.ACTION, e -> {
            fields.forEach((a, f) -> f.setText(a.defaultStroke().toString()));
            e.consume();
        });
        dialog.getDialogPane().lookupButton(openFile).addEventFilter(ActionEvent.ACTION, e -> {
            c.openPath(keys.file()).thenAccept(this::reportUnaudited);
            e.consume();
        });
        if (dialog.showAndWait().filter(b -> b == save).isEmpty()) {
            return;
        }
        List<String> problems = applyShortcuts(keys, fields);
        Thread.ofVirtual().name("dwb-ui-keys-save").start(() -> {
            try {
                keys.save();
                toasts.post(problems.isEmpty() ? Severity.INFO : Severity.WARNING, problems.isEmpty()
                        ? "Kısayollar kaydedildi" : "Kısayollar kaydedildi; uygulanamayanlar: "
                        + String.join("; ", problems));
            } catch (IOException e) {
                toasts.post(Severity.WARNING, "Kısayollar kaydedilemedi: " + e.getMessage());
            }
        });
    }

    private static void captureStroke(TextField field, KeyEvent e) {
        KeyCode code = e.getCode();
        if (code == KeyCode.TAB || code == KeyCode.ESCAPE || code.isModifierKey()) {
            return; // keep dialog navigation working; modifiers alone are not a stroke
        }
        if ((code == KeyCode.BACK_SPACE || code == KeyCode.DELETE) && !e.isControlDown() && !e.isAltDown()
                && !e.isShiftDown() && !e.isMetaDown()) {
            field.setText("");
        } else {
            KeyStroke stroke = toStroke(e);
            if (stroke != null) {
                field.setText(stroke.toString());
            }
        }
        e.consume();
    }

    /**
     * Two passes, so swapping two shortcuts works: changed actions are unbound first, then bound without force,
     * which turns a genuine clash into a reported conflict instead of a silent reassignment.
     */
    private static List<String> applyShortcuts(KeyMapRegistry keys, java.util.Map<Action, TextField> fields) {
        java.util.Map<Action, String> wanted = new EnumMap<>(Action.class);
        fields.forEach((a, f) -> wanted.put(a, f.getText().strip()));
        List<String> problems = new ArrayList<>();
        for (BindResult r : keys.bindAll(wanted)) {
            switch (r) {
                case BindResult.Bound b -> {
                }
                case BindResult.Conflict(Action x, KeyStroke st, Action holder) ->
                        problems.add(st + " zaten '" + describe(holder) + "' için kullanılıyor");
                case BindResult.Invalid(Action x, String raw, String reason) ->
                        problems.add("'" + raw + "': " + reason);
            }
        }
        return problems;
    }

    private static String describe(Action a) {
        return switch (a) {
            case FOCUS_SEARCH -> "Arama çubuğuna odaklan";
            case TOGGLE_TERMINAL -> "Terminali aç/kapat";
            case OPEN_SELECTED -> "Seçili belgeyi aç";
            case SHOW_IN_EXPLORER -> "Klasörde göster";
            case TOGGLE_MODE -> "Basit / IT görünümü";
            case TOGGLE_FILE_TREE -> "Dosya panelini aç/kapat";
            case SAVE_INDEX -> "İndeksi kaydet";
            case CLEAR_TERMINAL -> "Terminali temizle";
            case TOGGLE_FOCUS -> "Odak modu";
            case PREVIEW_SELECTED -> "Önizleme";
            case ADD_FILES -> "Belge ekle (dosya seçici)";
        };
    }

    /**
     * IT administration page ({@link AdminPanelDialog}). It shows state and policy directly and turns every change
     * into the matching terminal command ({@code admin}, {@code blacklist}, {@code limit}, {@code audit}, {@code gc}),
     * so access control, central-policy locks, audit entries and secret redaction stay where the admin engine
     * enforces them.
     */
    private void showAdminPanel(boolean devices) {
        WorkbenchController c = controller;
        if (c == null) {
            return;
        }
        AdminPanelDialog dialog = styled(new AdminPanelDialog(ext, c, line -> runCommand(c, line),
                path -> c.revealPath(path).thenAccept(this::reportUnaudited)));
        if (devices) {
            dialog.showDevices();
        }
        dialog.show();
    }

    // ================================================================== LAN security indicators

    /** FX thread: the badge for the current LAN security mode (cheap snapshot, polled every 2 s). */
    private void updateSecurityBadge() {
        ExtendedWorkbenchController.LanShield shield = ext.lanShield();
        statusSecurity.getStyleClass().removeAll("security-zero-trust", "security-legacy", "security-open",
                "security-off");
        switch (shield.kind()) {
            case ZERO_TRUST -> {
                statusSecurity.setText("🔒 Zero-Trust aktif [v3 şifreli]");
                statusSecurity.getStyleClass().add("security-zero-trust");
                securityTip.setText("Sıfır güven modu · protokol v3\n"
                        + "Cihaz kimliği (Ed25519): " + org.example.p2p.DeviceIdentity.display(shield.fingerprint()) + "\n"
                        + "Tünel: X25519 + HKDF-SHA256 + AES-256-GCM (her oturumda yeni anahtar)\n"
                        + "Dosya bütünlüğü: SHA-256 · güvenilen cihaz: " + shield.trusted() + " · TCP " + shield.transferPort()
                        + "\nTıklayın: cihaz yönetimi");
            }
            case LEGACY_SECRET -> {
                statusSecurity.setText("Legacy Mod · DWB_SECRET (şifresiz)");
                statusSecurity.getStyleClass().add("security-legacy");
                securityTip.setText("Eski protokol v1: eşler ortak sırla doğrulanır, aktarım şifrelenmez.\n"
                        + "Sıfır güven için DWB_TRUST=legacy ayarını kaldırın.");
            }
            case LEGACY_OPEN -> {
                statusSecurity.setText("⚠ Legacy Mod · açık (şifresiz)");
                statusSecurity.getStyleClass().add("security-open");
                securityTip.setText("Eski protokol v1, kimlik doğrulamasız: ağdaki herkes belgeleri görebilir.\n"
                        + "Sıfır güven için DWB_TRUST=legacy ayarını kaldırın.");
            }
            case STARTING -> {
                statusSecurity.setText("LAN başlatılıyor…");
                statusSecurity.getStyleClass().add("security-off");
                securityTip.setText("Ağ eşitleme başlatılıyor");
            }
            case OFF -> {
                statusSecurity.setText("LAN kapalı");
                statusSecurity.getStyleClass().add("security-off");
                securityTip.setText("Ağ eşitleme kapalı: " + shield.reason());
            }
        }
    }

    /** FX thread: a lock (encrypted tunnel) or warning (legacy, plain) for a finished transfer, for a few seconds. */
    private void showSecureTransfer(boolean outgoing, String name, String peer, long bytes, boolean encrypted) {
        if (closed) {
            return;
        }
        String arrow = outgoing ? "⇡ " + name + " → " + peer : "⇣ " + name + " ← " + peer;
        statusSecureTransfer.setText((encrypted ? "🔒 " : "⚠ ") + arrow + (encrypted ? " · şifreli ✓" : " · şifresiz"));
        statusSecureTransfer.getStyleClass().removeAll("secure-transfer-plain");
        if (!encrypted) {
            statusSecureTransfer.getStyleClass().add("secure-transfer-plain");
        }
        secureTransferTip.setText((outgoing ? "Gönderildi: " : "Alındı: ") + name + " (" + bytes + " bayt)\n"
                + (encrypted ? "Uçtan uca şifreli tünel (AES-256-GCM), karşı cihaz kimliği doğrulandı,"
                + " içerik SHA-256 ile doğrulandı." : "Legacy mod: içerik SHA-256 ile doğrulandı ama aktarım"
                + " şifrelenmedi."));
        statusSecureTransfer.setVisible(true);
        statusSecureTransfer.setManaged(true);
        secureTransferHide.playFromStart();
    }

    /**
     * Opens (or brings back) the non-modal command guide. "Dene" and the sandbox buttons only fill the command bar;
     * the user still presses Enter.
     */
    private void showCommandGuide() {
        if (commandGuide != null && commandGuide.isShowing()) {
            ((Stage) commandGuide.getDialogPane().getScene().getWindow()).toFront();
            return;
        }
        CommandGuideDialog guide = styled(new CommandGuideDialog(this::placeCommand));
        guide.setOnHidden(e -> commandGuide = null);
        commandGuide = guide;
        guide.show();
    }

    /** Places {@code line} in the command bar (its prefix selects the mode) with the caret at {@code caret}. */
    private void placeCommand(String line, int caret) {
        if (controller == null) {
            toasts.post(Severity.INFO, "Proje henüz yüklenmedi");
            return;
        }
        stage.requestFocus();
        commandBar.placeLine(line, caret);
    }

    private static Label sectionLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("section-title");
        return l;
    }

    // ================================================================== misc

    private void status(String message) {
        Fx.run(() -> statusMessage.setText(message));
    }

    private record TransferState(String peer, long transferred, long total) {
    }

    /** FX thread: records a large LAN download's progress and shows it in the status bar until it ends. */
    private void showTransfer(String what, String peer, long transferred, long total) {
        if (closed || total <= 0) {
            return;
        }
        transfers.remove(what); // re-inserted last: the most recently updated download is the one shown
        transfers.put(what, new TransferState(peer, transferred, total));
        renderTransfers();
    }

    /** FX thread: a download ended (verified or failed); the indicator stays while others are still running. */
    private void endTransfer(String what, boolean verified) {
        if (transfers.remove(what) == null) {
            return;
        }
        statusMessage.setText("LAN: " + what + (verified ? " alındı ve doğrulandı" : " aktarımı yarıda kaldı"));
        renderTransfers();
    }

    private void renderTransfers() {
        boolean active = !transfers.isEmpty();
        statusTransfer.setVisible(active);
        statusTransfer.setManaged(active);
        if (!active) {
            return;
        }
        // The most recently updated download is shown; the others are counted and listed in the tooltip.
        Map.Entry<String, TransferState> shown = null;
        StringBuilder tip = new StringBuilder();
        for (Map.Entry<String, TransferState> e : transfers.entrySet()) {
            if (!tip.isEmpty()) {
                tip.append('\n');
            }
            tip.append(describeTransfer(e.getKey(), e.getValue())).append(" ← ").append(e.getValue().peer());
            shown = e;
        }
        TransferState s = shown.getValue();
        transferBar.setProgress((double) s.transferred() / s.total());
        boolean encrypted = ext.lanShield().encrypted();
        String text = (encrypted ? "🔒 " : "") + "LAN ⇣ " + describeTransfer(shown.getKey(), s)
                + (s.transferred() >= s.total() ? " · doğrulanıyor…" : "")
                + (transfers.size() > 1 ? " (+" + (transfers.size() - 1) + ")" : "");
        transferLabel.setText(text);
        transferTip.setText(tip + (encrypted ? "\nŞifreli tünel: AES-256-GCM" : "\nLegacy mod: şifresiz aktarım"));
    }

    private static String describeTransfer(String what, TransferState s) {
        return String.format(Locale.ROOT, "%s · %.2f / %.2f GB · %%%d", what, s.transferred() / 1e9, s.total() / 1e9,
                s.transferred() * 100 / s.total());
    }

    public ToastNotifier toasts() {
        return toasts;
    }

    /** Releases listeners, timers and detached windows. The controller itself is closed by the application. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        heapTicker.stop();
        securityTicker.stop();
        secureTransferHide.stop();
        if (commandGuide != null) {
            commandGuide.close();
        }
        lifetime.forEach(Subscription::close);
        lifetime.clear();
        closeProjectBindings();
        unbindViews();
        ext.setNotifier(null);
        ext.setTransferObserver(null);
        toasts.clear();
    }

    // ================================================================== adapters

    private final class ResultActions implements ResultListView.Actions {
        @Override
        public void select(int row) {
            withController(c -> c.select(row));
        }

        @Override
        public void open(int row) {
            WorkbenchChassis.this.open(row);
        }

        @Override
        public void reveal(int row) {
            WorkbenchChassis.this.reveal(row);
        }

        @Override
        public void preview(int row) {
            withController(c -> c.select(row));
            previewRow(row);
        }

        @Override
        public void copyPath(int row) {
            rowDocument(row).ifPresent(WorkbenchChassis.this::copyPath);
        }

        @Override
        public void copyContent(int row) {
            rowDocument(row).ifPresent(WorkbenchChassis.this::copyContent);
        }

        @Override
        public void untrack(int row) {
            rowDocument(row).ifPresent(WorkbenchChassis.this::untrack);
        }

        @Override
        public void purge(int row) {
            rowDocument(row).ifPresent(WorkbenchChassis.this::purge);
        }

        @Override
        public void openSource(String docId) {
            withController(c -> c.document(docId).ifPresent(WorkbenchChassis.this::openDocument));
        }

        @Override
        public boolean isSystemDocument(String docId) {
            WorkbenchController c = controller;
            return c != null && c.document(docId).map(DocumentRecord::isSystemDocument).orElse(false);
        }
    }

    private final class TreeActions implements FileTreePanelView.Actions {
        @Override
        public void ingest(List<Path> paths) {
            WorkbenchChassis.this.ingest(paths);
        }

        @Override
        public void goHome() {
            browser.navigate(WorkspaceBrowserView.HOME);
        }

        @Override
        public void switchProject(ProjectProfile project) {
            WorkbenchChassis.this.switchProject(project);
        }

        @Override
        public void newProject() {
            WorkbenchChassis.this.newProject();
        }

        @Override
        public void openFolder(Path folder) {
            browser.navigate(new WorkspaceBrowserView.InFolder(folder));
        }

        @Override
        public void revealFolder(Path folder) {
            WorkbenchChassis.this.revealFolder(folder);
        }

        @Override
        public void copyFolderPath(Path folder) {
            WorkbenchChassis.this.copyFolderPath(folder);
        }

        @Override
        public void rescanFolder(Path folder) {
            ingest(List.of(folder));
        }

        @Override
        public void renameFolder(Path folder) {
            WorkbenchChassis.this.renameFolder(folder);
        }
    }

    private final class BrowserActions implements WorkspaceBrowserView.Actions {
        @Override
        public void open(DocumentRecord document) {
            openDocument(document);
        }

        @Override
        public void reveal(DocumentRecord document) {
            revealDocument(document);
        }

        @Override
        public void preview(DocumentRecord document) {
            previewDocument(document);
        }

        @Override
        public void copyPath(DocumentRecord document) {
            WorkbenchChassis.this.copyPath(document);
        }

        @Override
        public void copyContent(DocumentRecord document) {
            WorkbenchChassis.this.copyContent(document);
        }

        @Override
        public void untrack(DocumentRecord document) {
            WorkbenchChassis.this.untrack(document);
        }

        @Override
        public void purge(DocumentRecord document) {
            WorkbenchChassis.this.purge(document);
        }

        @Override
        public void untrackAll(List<DocumentRecord> documents) {
            WorkbenchChassis.this.untrackAll(documents);
        }

        @Override
        public void purgeAll(List<DocumentRecord> documents) {
            WorkbenchChassis.this.purgeAll(documents);
        }

        @Override
        public void revealFolder(Path folder) {
            WorkbenchChassis.this.revealFolder(folder);
        }

        @Override
        public void copyFolderPath(Path folder) {
            WorkbenchChassis.this.copyFolderPath(folder);
        }

        @Override
        public void rescanFolder(Path folder) {
            ingest(List.of(folder));
        }

        @Override
        public void renameFolder(Path folder) {
            WorkbenchChassis.this.renameFolder(folder);
        }

        @Override
        public void ingest(List<Path> paths) {
            WorkbenchChassis.this.ingest(paths);
        }

        @Override
        public void chooseFiles() {
            fileTree.chooseFiles();
        }

        @Override
        public void chooseFolder() {
            fileTree.chooseFolder();
        }

        @Override
        public void openAsset(BinaryAsset asset) {
            WorkbenchChassis.this.openAsset(asset);
        }

        @Override
        public void revealAsset(BinaryAsset asset) {
            WorkbenchChassis.this.revealAsset(asset);
        }

        @Override
        public void copyAssetPath(BinaryAsset asset) {
            WorkbenchChassis.this.copyAssetPath(asset);
        }

        @Override
        public void untrackAsset(BinaryAsset asset) {
            WorkbenchChassis.this.untrackAsset(asset);
        }
    }
}
