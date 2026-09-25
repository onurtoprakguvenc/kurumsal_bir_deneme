package org.example.ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.example.project.ProjectProfile;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The {@code FILE_TREE} panel, reduced to a flat navigation list: the projects (workspaces) and the root folders of
 * the active project, with the item counter pinned at the bottom ({@code 14 belge • 1163 parça}). There are no drive
 * hierarchies, cloud accounts or nested branches here — the folders below a root are browsed in the explorer.
 *
 * <p>A click (or {@code Enter}) activates a row: the active project row goes to the Home dashboard, another project
 * row switches to that project, a root row opens the folder in the explorer. Selection alone never navigates, so the
 * arrow keys only move the highlight. Files and folders dropped on the panel are handed to the chassis for
 * ingestion; the panel never decides anything itself.</p>
 */
public final class FileTreePanelView extends VBox {

    /** What the panel asks of the chassis. */
    public interface Actions {
        void ingest(List<Path> paths);

        void goHome();

        void switchProject(ProjectProfile project);

        void newProject();

        void openFolder(Path folder);

        void revealFolder(Path folder);

        void copyFolderPath(Path folder);

        void rescanFolder(Path folder);

        void renameFolder(Path folder);
    }

    /** A root folder row: the folder, its (possibly custom) title and how many documents it holds. */
    public record Root(Path path, String title, int documents) {
    }

    private sealed interface Item permits Header, ProjectItem, RootItem {
    }

    private record Header(String text) implements Item {
    }

    private record ProjectItem(ProjectProfile profile, boolean active) implements Item {
    }

    private record RootItem(Root root) implements Item {
    }

    private final ObservableList<Item> items = FXCollections.observableArrayList();
    private final ListView<Item> list = new ListView<>(items);
    private final Label counter = new Label("0 belge • 0 parça");
    private final Label busy = new Label();
    private final ContextMenu rootMenu = new ContextMenu();
    private final ContextMenu projectMenu = new ContextMenu();
    private final Actions actions;
    private List<ProjectProfile> projects = List.of();
    private String activeProjectId = "";
    private List<Root> roots = List.of();
    private Path current;
    private Item menuTarget;
    private Window ownerFallback;

    public FileTreePanelView(Actions actions) {
        this.actions = Objects.requireNonNull(actions);
        getStyleClass().add("nav-pane");
        setMinWidth(170);

        list.getStyleClass().add("nav-list");
        list.setCellFactory(lv -> new NavCell());
        list.setPlaceholder(new Label(""));
        list.addEventHandler(KeyEvent.KEY_PRESSED, this::onKey);
        VBox.setVgrow(list, Priority.ALWAYS);
        buildMenus();

        busy.getStyleClass().add("nav-busy");
        busy.setWrapText(true);
        busy.setManaged(false);
        busy.setVisible(false);
        counter.getStyleClass().add("nav-counter");
        counter.setTooltip(new Tooltip("Etkin projedeki indekslenmiş belge ve parça sayısı"));
        VBox footer = new VBox(4, busy, counter);
        footer.getStyleClass().add("nav-footer");
        footer.setPadding(new Insets(8, 14, 10, 14));

        getChildren().addAll(list, footer);
        installDropTarget();
        rebuild();
    }

    // ================================================================== menus & keyboard

    private void buildMenus() {
        MenuItem open = menuItem("Aç", () -> withRoot(actions::openFolder));
        MenuItem reveal = menuItem("Dosya Gezgininde Göster", () -> withRoot(actions::revealFolder));
        MenuItem copy = menuItem("Yolu Kopyala", () -> withRoot(actions::copyFolderPath));
        MenuItem rescan = menuItem("Yeniden Tara", () -> withRoot(actions::rescanFolder));
        MenuItem rename = menuItem("Yeniden Adlandır…", () -> withRoot(actions::renameFolder));
        rootMenu.getItems().addAll(open, reveal, copy, new SeparatorMenuItem(), rescan, rename);

        MenuItem activate = menuItem("Aç", () -> {
            if (menuTarget != null) {
                activate(menuTarget);
            }
        });
        MenuItem create = menuItem("+ Yeni Proje…", actions::newProject);
        projectMenu.getItems().addAll(activate, new SeparatorMenuItem(), create);
    }

    private static MenuItem menuItem(String text, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(e -> action.run());
        return item;
    }

    private void withRoot(java.util.function.Consumer<Path> action) {
        if (menuTarget instanceof RootItem(Root root)) {
            action.accept(root.path());
        }
    }

    private void onKey(KeyEvent e) {
        Item selected = list.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        if (e.getCode() == KeyCode.ENTER && !e.isShortcutDown()) {
            activate(selected);
        } else if (e.getCode() == KeyCode.CONTEXT_MENU) {
            menuTarget = selected;
            ContextMenu menu = selected instanceof RootItem ? rootMenu : projectMenu;
            menu.show(list, javafx.geometry.Side.BOTTOM, 0, 0);
        } else {
            return;
        }
        e.consume();
    }

    private void activate(Item item) {
        switch (item) {
            case Header h -> {
            }
            case ProjectItem(ProjectProfile p, boolean active) -> {
                if (active) {
                    actions.goHome();
                } else {
                    actions.switchProject(p);
                }
            }
            case RootItem(Root root) -> actions.openFolder(root.path());
        }
    }

    // ================================================================== ingestion

    /** Opens a multi-select file chooser; chosen files are ingested. */
    public void chooseFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Belge Ekle");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Desteklenen belgeler", java.util.stream.Stream.concat(
                        java.util.stream.Stream.of("*.pdf", "*.docx", "*.xlsx", "*.pptx", "*.ppt", "*.csv", "*.txt"),
                        org.example.model.ContentKind.binaryPatterns().stream()).toList()),
                new FileChooser.ExtensionFilter("Tüm dosyalar", "*.*"));
        List<File> files = chooser.showOpenMultipleDialog(window());
        if (files != null && !files.isEmpty()) {
            actions.ingest(files.stream().map(f -> f.toPath().toAbsolutePath().normalize()).toList());
        }
    }

    public void chooseFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Klasör Ekle");
        File dir = chooser.showDialog(window());
        if (dir != null) {
            actions.ingest(List.of(dir.toPath().toAbsolutePath().normalize()));
        }
    }

    /**
     * The window the choosers are modal to when this panel is not on screen itself — it is undocked in focus mode and
     * can be hidden or detached at any time, and an ownerless chooser would open behind the main window.
     */
    public void setOwnerFallback(Window window) {
        this.ownerFallback = window;
    }

    private Window window() {
        return getScene() == null ? ownerFallback : getScene().getWindow();
    }

    private void installDropTarget() {
        setOnDragOver(e -> {
            if (e.getGestureSource() == null && e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        setOnDragEntered(e -> {
            if (e.getDragboard().hasFiles() && !getStyleClass().contains("drop-active")) {
                getStyleClass().add("drop-active");
            }
        });
        setOnDragExited(e -> getStyleClass().remove("drop-active"));
        setOnDragDropped(this::dropped);
    }

    private void dropped(DragEvent e) {
        Dragboard board = e.getDragboard();
        boolean accepted = board.hasFiles();
        if (accepted) {
            List<Path> paths = new ArrayList<>(board.getFiles().size());
            for (File f : board.getFiles()) {
                paths.add(f.toPath().toAbsolutePath().normalize());
            }
            actions.ingest(paths);
        }
        getStyleClass().remove("drop-active");
        e.setDropCompleted(accepted);
        e.consume();
    }

    // ================================================================== content

    /** Project rows; {@code activeId} marks the one whose roots are listed below. */
    public void setProjects(List<ProjectProfile> projects, String activeId) {
        this.projects = List.copyOf(projects);
        this.activeProjectId = Objects.requireNonNullElse(activeId, "");
        rebuild();
    }

    /** Root folders of the active project plus the totals for the bottom counter. */
    public void setRoots(List<Root> roots, int documents, long chunks) {
        this.roots = List.copyOf(roots);
        counter.setText(String.format(Locale.ROOT, "%,d belge • %,d parça", documents, chunks)
                .replace(',', '.'));
        rebuild();
    }

    /**
     * Highlights the row for the current location: the root that contains {@code folder}, or the active project on
     * the Home dashboard ({@code folder == null}).
     */
    public void setCurrent(Path folder) {
        this.current = folder;
        syncSelection();
    }

    public void setBusy(String message) {
        busy.setText(message == null ? "" : message);
        busy.setManaged(message != null);
        busy.setVisible(message != null);
    }

    private void rebuild() {
        List<Item> next = new ArrayList<>(projects.size() + roots.size() + 2);
        next.add(new Header("PROJELER"));
        if (projects.isEmpty() && !activeProjectId.isEmpty()) {
            next.add(new ProjectItem(null, true));
        }
        for (ProjectProfile p : projects) {
            next.add(new ProjectItem(p, p.id().equals(activeProjectId)));
        }
        if (!roots.isEmpty()) {
            next.add(new Header("KLASÖRLER"));
            for (Root r : roots) {
                next.add(new RootItem(r));
            }
        }
        items.setAll(next);
        syncSelection();
    }

    private void syncSelection() {
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            boolean match = switch (item) {
                case Header h -> false;
                case ProjectItem pi -> current == null && pi.active();
                case RootItem(Root r) -> current != null && current.startsWith(r.path());
            };
            if (match) {
                list.getSelectionModel().clearAndSelect(i);
                return;
            }
        }
        list.getSelectionModel().clearSelection();
    }

    // ================================================================== cell

    private final class NavCell extends ListCell<Item> {
        private final Region icon = new Region();
        private final Label name = new Label();
        private final Label count = new Label();
        private final Button add = new Button("+");
        private final HBox row = new HBox(8, icon, name, count);

        NavCell() {
            icon.getStyleClass().add("nav-icon");
            icon.setMinSize(14, 14);
            icon.setMaxSize(14, 14);
            name.getStyleClass().add("nav-name");
            name.setMinWidth(0);
            name.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(name, Priority.ALWAYS);
            count.getStyleClass().add("nav-count");
            count.setMinWidth(USE_PREF_SIZE);
            add.getStyleClass().add("nav-add-button");
            add.setFocusTraversable(false);
            add.setTooltip(new Tooltip("Yeni proje oluştur"));
            add.setOnAction(e -> actions.newProject());
            row.setAlignment(Pos.CENTER_LEFT);
            setPrefWidth(0);

            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && !isEmpty() && getItem() != null) {
                    activate(getItem());
                }
            });
            setOnContextMenuRequested(e -> {
                Item item = getItem();
                if (isEmpty() || item == null || item instanceof Header) {
                    return;
                }
                menuTarget = item;
                ContextMenu menu = item instanceof RootItem ? rootMenu : projectMenu;
                menu.show(this, e.getScreenX(), e.getScreenY());
                e.consume();
            });
        }

        @Override
        protected void updateItem(Item item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("nav-header", "nav-project", "nav-root", "nav-active");
            setDisable(false);
            if (empty || item == null) {
                setGraphic(null);
                setTooltip(null);
                return;
            }
            icon.getStyleClass().removeAll("project-glyph", "folder-glyph");
            row.getChildren().remove(add);
            switch (item) {
                case Header(String text) -> {
                    getStyleClass().add("nav-header");
                    name.setText(text);
                    count.setText("");
                    icon.setVisible(false);
                    icon.setManaged(false);
                    if ("PROJELER".equals(text)) {
                        row.getChildren().add(add);
                    }
                    setTooltip(null);
                }
                case ProjectItem(ProjectProfile p, boolean active) -> {
                    getStyleClass().add("nav-project");
                    if (active) {
                        getStyleClass().add("nav-active");
                    }
                    icon.getStyleClass().add("project-glyph");
                    icon.setVisible(true);
                    icon.setManaged(true);
                    name.setText(p == null ? "Proje" : p.name());
                    count.setText(active ? "etkin" : "");
                    setTooltip(new Tooltip(active ? "Giriş panosuna dön" : "Bu projeye geç"));
                }
                case RootItem(Root r) -> {
                    getStyleClass().add("nav-root");
                    icon.getStyleClass().add("folder-glyph");
                    icon.setVisible(true);
                    icon.setManaged(true);
                    name.setText(r.title());
                    count.setText(String.valueOf(r.documents()));
                    setTooltip(new Tooltip(r.path().toString()));
                }
            }
            setGraphic(row);
        }
    }
}
