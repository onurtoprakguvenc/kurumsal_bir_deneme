package org.example.ui;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.example.model.BinaryAsset;
import org.example.model.DocumentRecord;
import org.example.model.DocumentType;
import org.example.ui.CommandBarView.Mode;

import java.io.File;
import java.nio.file.Path;
import java.text.Collator;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The centre of the window ({@code RESULTS} panel): a two-layer file workspace with its own back/forward history.
 *
 * <pre>
 * Home dashboard                          Explorer (inside a folder)          Search results
 * ┌──────────────────────────────────┐    ┌──────────────────────────────┐    ┌──────────────────────────┐
 * │ ˅ Hızlı erişim                   │    │ Ad        Tarih  Tür  Boyut  │    │ resultsSlot (the chassis │
 * │ [CMP3005] [PDF Arşivi] [Ödevler] │    │ 📁 hafta4                    │    │  mounts ResultListView)  │
 * │ ˅ En son eklenenler              │    │ PDF  slides.pdf              │    │                          │
 * │ Ad · Tarih · Tür · Boyut · Parça │    │ TXT  notlar.txt              │    │                          │
 * └──────────────────────────────────┘    └──────────────────────────────┘    └──────────────────────────┘
 * </pre>
 *
 * <p>Folders come from a {@link FolderIndex} — the indexed documents' own paths — so nothing here touches the disk.
 * Both tables are virtualized {@link TableView}s; folder cards are plain buttons in a {@link FlowPane}. Only the
 * layer for the current {@link Place} is in the scene graph.</p>
 *
 * <p>Interaction: a card opens its folder on click; in the tables a double click (or {@code Enter}) enters a folder
 * or opens a document, {@code Backspace} goes back. {@code F3} previews the selected document ({@code Space} is routed
 * by the chassis, which owns the Quick Look overlay), {@code Delete} untracks, {@code Shift+Delete} deletes from disk,
 * {@code Ctrl+C} / {@code Ctrl+Shift+C} copy path / content. Every row also has a context menu and hover actions.
 * Every action goes to the chassis through {@link Actions}.</p>
 *
 * <p>Selection works like a native file manager: a click selects one row, {@code Shift+click} extends a contiguous
 * range from the anchor, {@code Ctrl+click} ({@code Cmd} on macOS) toggles single rows and {@code Ctrl+A} selects the
 * whole view. Right-clicking inside the selection keeps it, outside it selects that row only. With several documents
 * selected, "İndeksten Kaldır" / {@code Delete} and "Diskten Tamamen Sil…" / {@code Shift+Delete} act on all of them
 * at once; the other items act on the row that was clicked.</p>
 */
public final class WorkspaceBrowserView extends StackPane {

    /** Where the browser is. */
    public sealed interface Place permits Home, InFolder, Results {
    }

    public record Home() implements Place {
    }

    public record InFolder(Path path) implements Place {
        public InFolder {
            Objects.requireNonNull(path, "path must not be null");
        }
    }

    /** The search/answer view; only the latest one is kept in the history. */
    public record Results(Mode mode, String line) implements Place {
    }

    public static final Place HOME = new Home();

    /** Sort keys shared by both tables (the toolbar's "Sırala" menu). */
    public enum SortKey {
        NAME("Ad"), DATE("İndeks tarihi"), TYPE("Tür"), SIZE("Boyut"), CHUNKS("Parça sayısı");

        private final String label;

        SortKey(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** What the browser asks of the chassis. */
    public interface Actions {
        void open(DocumentRecord document);

        void reveal(DocumentRecord document);

        void preview(DocumentRecord document);

        void copyPath(DocumentRecord document);

        void copyContent(DocumentRecord document);

        void untrack(DocumentRecord document);

        void purge(DocumentRecord document);

        /** Index-only removal of a multi-selection (one summary, one save); defaults to one by one. */
        default void untrackAll(List<DocumentRecord> documents) {
            documents.forEach(this::untrack);
        }

        /** Disk deletion of a multi-selection (one confirmation); defaults to one by one. */
        default void purgeAll(List<DocumentRecord> documents) {
            documents.forEach(this::purge);
        }

        void revealFolder(Path folder);

        void copyFolderPath(Path folder);

        void rescanFolder(Path folder);

        void renameFolder(Path folder);

        void ingest(List<Path> paths);

        void chooseFiles();

        void chooseFolder();

        /** Opens a binary asset (video, image, audio, archive) with its default OS application. */
        default void openAsset(BinaryAsset asset) {
        }

        default void revealAsset(BinaryAsset asset) {
        }

        default void copyAssetPath(BinaryAsset asset) {
        }

        /** Stops sharing the asset and removes it from the list; the file on disk is not touched. */
        default void untrackAsset(BinaryAsset asset) {
        }
    }

    private sealed interface Entry permits FolderEntry, DocEntry, AssetEntry {
    }

    private record FolderEntry(FolderIndex.Folder folder) implements Entry {
    }

    private record DocEntry(DocumentRecord document) implements Entry {
    }

    /** A binary asset: listed and opened like a document, but it has no text, chunks or preview. */
    private record AssetEntry(BinaryAsset asset) implements Entry {
    }

    private static final int HISTORY = 50;
    private static final int RECENT_LIMIT = 200;
    private static final double TILE_AREA_MAX = 236;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final Collator COLLATOR = Collator.getInstance(Locale.forLanguageTag("tr"));
    private static final Comparator<Entry> FOLDERS_FIRST =
            Comparator.comparingInt(e -> e instanceof FolderEntry ? 0 : 1);

    private final Actions actions;

    private final VBox homeView = new VBox();
    private final FlowPane tiles = new FlowPane(10, 10);
    private final ScrollPane tileScroll = new ScrollPane(tiles);
    private final Button quickHeader = new Button();
    private final Button recentHeader = new Button();
    private final ObservableList<Entry> recentRows = FXCollections.observableArrayList();
    private final TableView<Entry> recentTable;

    private final VBox explorerView = new VBox();
    private final ObservableList<Entry> folderRows = FXCollections.observableArrayList();
    private final TableView<Entry> folderTable;

    private final StackPane resultsSlot = new StackPane();

    private final ContextMenu docMenu = new ContextMenu();
    private final ContextMenu folderMenu = new ContextMenu();
    private final ContextMenu assetMenu = new ContextMenu();
    private MenuItem assetGoToFolder;
    private MenuItem docOpen;
    private MenuItem docReveal;
    private MenuItem docPurge;
    private MenuItem docUntrack;
    private MenuItem docGoToFolder;
    private Entry menuTarget;
    /** The documents "İndeksten Kaldır" / "Diskten Sil" act on: the selection when the menu opened on it. */
    private List<DocumentRecord> menuBatch = List.of();

    private final Deque<Place> backStack = new ArrayDeque<>();
    private final Deque<Place> forwardStack = new ArrayDeque<>();
    private Place place = HOME;
    private FolderIndex index = FolderIndex.EMPTY;
    private Map<Path, String> titles = Map.of();
    private boolean quickCollapsed;
    private boolean quickAllowed = true;
    private Runnable onPlaceChanged = () -> { };

    public WorkspaceBrowserView(Actions actions) {
        this.actions = Objects.requireNonNull(actions, "actions must not be null");
        getStyleClass().add("workspace-browser");
        setMinSize(0, 0);
        buildMenus();

        recentTable = entryTable(recentRows, true);
        folderTable = entryTable(folderRows, false);
        buildHome();

        explorerView.getStyleClass().add("explorer-view");
        explorerView.setPadding(new Insets(6, 16, 0, 16));
        VBox.setVgrow(folderTable, Priority.ALWAYS);
        folderTable.setPlaceholder(placeholder("Bu klasörde indekslenmiş belge yok."));
        explorerView.getChildren().add(folderTable);

        resultsSlot.getStyleClass().add("results-slot");
        installDropTarget();
        show();
    }

    // ================================================================== home dashboard

    private void buildHome() {
        homeView.getStyleClass().add("home-view");
        homeView.setPadding(new Insets(10, 16, 0, 16));
        homeView.setSpacing(6);

        quickHeader.getStyleClass().add("section-toggle");
        quickHeader.setFocusTraversable(false);
        quickHeader.setOnAction(e -> {
            quickCollapsed = !quickCollapsed;
            syncQuickAccess();
        });

        tiles.getStyleClass().add("folder-tiles");
        tiles.setPadding(new Insets(2, 0, 8, 0));
        tileScroll.getStyleClass().add("tile-scroll");
        tileScroll.setFitToWidth(true);
        tileScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        // As tall as the cards need, at most two rows' worth; more cards scroll inside the area.
        tiles.heightProperty().addListener((obs, old, h) ->
                tileScroll.setPrefViewportHeight(Math.min(h.doubleValue(), TILE_AREA_MAX)));
        tileScroll.setMinViewportHeight(0);

        recentHeader.getStyleClass().addAll("section-toggle", "static");
        recentHeader.setFocusTraversable(false);
        recentHeader.setMouseTransparent(true);

        recentTable.setPlaceholder(emptyWorkspace());
        VBox.setVgrow(recentTable, Priority.ALWAYS);
        homeView.getChildren().addAll(quickHeader, tileScroll, recentHeader, recentTable);
        syncQuickAccess();
    }

    /**
     * Focus mode's switch for the whole "Hızlı erişim" card (its header and the folder tiles). It is independent of
     * the user's own collapse toggle, so whichever of the two states they left the card in is what they get back when
     * focus mode ends.
     */
    public void setQuickAccessVisible(boolean visible) {
        if (quickAllowed == visible) {
            return;
        }
        quickAllowed = visible;
        syncQuickAccess();
    }

    private void syncQuickAccess() {
        int n = index.roots().size();
        quickHeader.setText((quickCollapsed ? "›  " : "˅  ") + "Hızlı erişim" + (n > 0 ? "   ·   " + n + " klasör" : ""));
        boolean card = quickAllowed && n > 0;
        boolean show = card && !quickCollapsed;
        tileScroll.setVisible(show);
        tileScroll.setManaged(show);
        quickHeader.setVisible(card);
        quickHeader.setManaged(card);
    }

    private void rebuildTiles() {
        List<Node> cards = new ArrayList<>(index.roots().size());
        for (FolderIndex.Folder f : index.roots()) {
            cards.add(tile(f));
        }
        tiles.getChildren().setAll(cards);
        syncQuickAccess();
    }

    private Button tile(FolderIndex.Folder folder) {
        Region glyph = new Region();
        glyph.getStyleClass().addAll("folder-glyph", "large");
        Label title = new Label(title(folder));
        title.getStyleClass().add("tile-title");
        Label meta = new Label(folder.documents() + " belge · " + folder.chunks() + " parça"
                + (folder.assets() > 0 ? " · " + folder.assets() + " medya" : ""));
        meta.getStyleClass().add("tile-meta");
        Path parent = folder.path().getParent();
        Label where = new Label(parent == null ? folder.path().toString() : parent.toString());
        where.getStyleClass().add("tile-path");
        VBox text = new VBox(1, title, meta, where);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox body = new HBox(10, glyph, text);
        body.setAlignment(Pos.CENTER_LEFT);

        Button tile = new Button();
        tile.getStyleClass().add("folder-tile");
        tile.setGraphic(body);
        tile.setMnemonicParsing(false);
        tile.setTooltip(new Tooltip(folder.path().toString()));
        tile.setOnAction(e -> navigate(new InFolder(folder.path())));
        tile.setOnContextMenuRequested(e -> {
            showMenu(new FolderEntry(folder), tile, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        return tile;
    }

    private Node emptyWorkspace() {
        Label text = new Label("Henüz belge yok.\nPDF, DOCX, XLSX, PPTX, CSV, TXT dosyalarını ya da video, fotoğraf ve"
                + " arşivleri buraya sürükleyip bırakın.");
        text.getStyleClass().add("placeholder");
        text.setWrapText(true);
        Button files = new Button("+ Dosya Ekle");
        files.getStyleClass().addAll("ghost-button", "accent-button");
        files.setOnAction(e -> actions.chooseFiles());
        Button folder = new Button("+ Klasör Ekle");
        folder.getStyleClass().add("ghost-button");
        folder.setOnAction(e -> actions.chooseFolder());
        HBox buttons = new HBox(8, files, folder);
        buttons.setAlignment(Pos.CENTER);
        VBox box = new VBox(12, text, buttons);
        box.setAlignment(Pos.CENTER);
        box.setMaxWidth(420);
        return box;
    }

    private static Node placeholder(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("placeholder");
        return l;
    }

    // ================================================================== tables

    private TableView<Entry> entryTable(ObservableList<Entry> rows, boolean withLocation) {
        TableView<Entry> table = new TableView<>(rows);
        table.getStyleClass().add("explorer-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setMinHeight(0);
        // Shift+click ranges, Ctrl/Cmd+click toggles and Ctrl/Cmd+A are the TableView's native MULTIPLE behaviour.
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        TableColumn<Entry, Entry> name = column("Ad", SortKey.NAME, 380, nameOrder());
        name.setCellFactory(c -> new NameCell(withLocation));
        TableColumn<Entry, Entry> date = column("İndeks tarihi", SortKey.DATE, 140,
                Comparator.comparing(WorkspaceBrowserView::dateOf, Comparator.nullsFirst(Comparator.naturalOrder())));
        date.setCellFactory(c -> textCell(e -> {
            Instant t = dateOf(e);
            return t == null ? "" : DATE.format(t);
        }, false));
        TableColumn<Entry, Entry> type = column("Tür", SortKey.TYPE, 150,
                Comparator.comparing(WorkspaceBrowserView::typeOf, COLLATOR));
        type.setCellFactory(c -> textCell(WorkspaceBrowserView::typeOf, false));
        TableColumn<Entry, Entry> size = column("Boyut", SortKey.SIZE, 90,
                Comparator.comparingLong(WorkspaceBrowserView::bytesOf));
        size.setCellFactory(c -> textCell(e -> switch (e) {
            case DocEntry(DocumentRecord d) -> size(d.sizeBytes());
            case AssetEntry(BinaryAsset a) -> size(a.sizeBytes());
            case FolderEntry f -> "";
        }, true));
        TableColumn<Entry, Entry> chunks = column("Parça", SortKey.CHUNKS, 70,
                Comparator.comparingLong(WorkspaceBrowserView::chunksOf));
        chunks.setCellFactory(c -> textCell(e -> e instanceof AssetEntry ? "—" : String.valueOf(chunksOf(e)), true));
        table.getColumns().setAll(List.of(name, date, type, size, chunks));

        // Folders always stay above documents, whatever column is sorted.
        table.setSortPolicy(t -> {
            Comparator<Entry> byColumn = t.getComparator();
            FXCollections.sort(rows, byColumn == null ? FOLDERS_FIRST.thenComparing(nameOrder())
                    : FOLDERS_FIRST.thenComparing(byColumn));
            return true;
        });

        table.setRowFactory(tv -> {
            TableRow<Entry> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !row.isEmpty()) {
                    activate(row.getItem());
                }
            });
            row.setOnContextMenuRequested(e -> {
                if (!row.isEmpty() && row.getItem() != null) {
                    selectForAction(tv, row.getIndex());
                    showMenu(row.getItem(), tv, row, e.getScreenX(), e.getScreenY());
                    e.consume();
                }
            });
            return row;
        });
        table.addEventHandler(KeyEvent.KEY_PRESSED, e -> onTableKey(table, e));
        return table;
    }

    private static TableColumn<Entry, Entry> column(String title, SortKey key, double width,
                                                   Comparator<Entry> comparator) {
        TableColumn<Entry, Entry> c = new TableColumn<>(title);
        c.setUserData(key);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue()));
        c.setComparator(comparator);
        c.setReorderable(false);
        return c;
    }

    private static TableCell<Entry, Entry> textCell(Function<Entry, String> text, boolean numeric) {
        TableCell<Entry, Entry> cell = new TableCell<>() {
            @Override
            protected void updateItem(Entry e, boolean empty) {
                super.updateItem(e, empty);
                setText(empty || e == null ? null : text.apply(e));
            }
        };
        cell.getStyleClass().add(numeric ? "numeric-cell" : "meta-cell");
        return cell;
    }

    private Comparator<Entry> nameOrder() {
        return Comparator.comparing(this::nameOf, COLLATOR);
    }

    private String nameOf(Entry e) {
        return switch (e) {
            case FolderEntry(FolderIndex.Folder f) -> title(f);
            case DocEntry(DocumentRecord d) -> d.fileName();
            case AssetEntry(BinaryAsset a) -> a.fileName();
        };
    }

    private static Instant dateOf(Entry e) {
        return switch (e) {
            case FolderEntry(FolderIndex.Folder f) -> f.latest();
            case DocEntry(DocumentRecord d) -> d.ingestedAt();
            case AssetEntry(BinaryAsset a) -> a.registeredAt();
        };
    }

    private static String typeOf(Entry e) {
        return switch (e) {
            case FolderEntry f -> "Dosya klasörü";
            case DocEntry(DocumentRecord d) -> typeName(d.type()) + (d.local() ? "" : " · " + d.origin());
            case AssetEntry(BinaryAsset a) -> MediaCategory.of(a.fileName()).typeName;
        };
    }

    private static long bytesOf(Entry e) {
        return switch (e) {
            case FolderEntry(FolderIndex.Folder f) -> f.bytes();
            case DocEntry(DocumentRecord d) -> d.sizeBytes();
            case AssetEntry(BinaryAsset a) -> a.sizeBytes();
        };
    }

    private static long chunksOf(Entry e) {
        return switch (e) {
            case FolderEntry(FolderIndex.Folder f) -> f.chunks();
            case DocEntry(DocumentRecord d) -> d.chunks().size();
            case AssetEntry a -> 0;
        };
    }

    static String typeName(DocumentType type) {
        return switch (type) {
            case PDF -> "PDF Belgesi";
            case DOCX -> "Word Belgesi";
            case DOC -> "Word 97-2003 Belgesi";
            case XLSX -> "Excel Çalışma Sayfası";
            case XLS -> "Excel 97-2003 Çalışma Sayfası";
            case PPTX, PPT -> "PowerPoint Sunusu";
            case CSV -> "CSV Dosyası";
            case TXT -> "Metin Belgesi";
        };
    }

    /** Badge text and "Tür" column label of a binary asset, from its file extension. */
    enum MediaCategory {
        VIDEO("VİDEO", "Video Dosyası", "mp4", "m4v", "mkv", "mov", "avi", "wmv", "webm", "flv", "mpg", "mpeg",
                "m2ts", "mts", "3gp", "ts"),
        IMAGE("GÖRSEL", "Görüntü Dosyası", "jpg", "jpeg", "png", "gif", "bmp", "tif", "tiff", "webp", "heic", "heif",
                "raw", "cr2", "nef", "arw", "dng", "psd", "ico"),
        AUDIO("SES", "Ses Dosyası", "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma"),
        ARCHIVE("ARŞİV", "Arşiv Dosyası", "zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst", "iso", "img",
                "dmg", "cab"),
        OTHER("DOSYA", "İkili Dosya");

        final String badge;
        final String typeName;
        private final java.util.Set<String> extensions;

        MediaCategory(String badge, String typeName, String... extensions) {
            this.badge = badge;
            this.typeName = typeName;
            this.extensions = java.util.Set.of(extensions);
        }

        static MediaCategory of(String fileName) {
            String lower = fileName.toLowerCase(Locale.ROOT);
            int dot = lower.lastIndexOf('.');
            String ext = dot < 0 ? "" : lower.substring(dot + 1);
            for (MediaCategory c : values()) {
                if (c.extensions.contains(ext)) {
                    return c;
                }
            }
            return OTHER;
        }
    }

    static String size(long bytes) {
        if (bytes >= 1L << 30) {
            return String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0);
        }
        return bytes >= 1 << 20 ? String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
                : Math.max(1, bytes >> 10) + " KB";
    }

    /** Keeps a multi-selection that already contains {@code index}; otherwise selects that row alone. */
    private static void selectForAction(TableView<Entry> table, int index) {
        if (!table.getSelectionModel().isSelected(index)) {
            table.getSelectionModel().clearAndSelect(index);
        } else {
            table.getFocusModel().focus(index);
        }
    }

    /** The selected documents (folders skipped), copied so later table changes cannot affect the caller. */
    private static List<DocumentRecord> selectedDocuments(TableView<Entry> table) {
        List<DocumentRecord> out = new ArrayList<>();
        for (Entry e : List.copyOf(table.getSelectionModel().getSelectedItems())) {
            if (e instanceof DocEntry(DocumentRecord d)) {
                out.add(d);
            }
        }
        return out;
    }

    /** The documents a batch action applies to when it was started on {@code entry}. */
    private static List<DocumentRecord> batchFor(TableView<Entry> table, Entry entry) {
        if (!(entry instanceof DocEntry(DocumentRecord d))) {
            return List.of();
        }
        List<DocumentRecord> selected = table == null ? List.of() : selectedDocuments(table);
        return selected.size() > 1 && selected.contains(d) ? selected : List.of(d);
    }

    private void untrack(List<DocumentRecord> documents) {
        if (documents.size() == 1) {
            actions.untrack(documents.getFirst());
        } else if (!documents.isEmpty()) {
            actions.untrackAll(documents);
        }
    }

    private void purge(List<DocumentRecord> documents) {
        if (documents.size() == 1) {
            actions.purge(documents.getFirst());
        } else if (!documents.isEmpty()) {
            actions.purgeAll(documents);
        }
    }

    private void onTableKey(TableView<Entry> table, KeyEvent e) {
        KeyCode code = e.getCode();
        if (code == KeyCode.BACK_SPACE && !e.isShortcutDown() && !e.isAltDown()) {
            back();
            e.consume();
            return;
        }
        Entry selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        if (code == KeyCode.ENTER && !e.isShortcutDown() && !e.isAltDown()) {
            activate(selected);
        } else if (code == KeyCode.CONTEXT_MENU || (code == KeyCode.F10 && e.isShiftDown())) {
            showMenu(selected, table, Side.TOP, 24, 32);
        } else if (code == KeyCode.DELETE && !e.isShortcutDown() && !selectedDocuments(table).isEmpty()) {
            // The whole selection, even when the focused row is a folder.
            List<DocumentRecord> documents = selectedDocuments(table);
            if (e.isShiftDown()) {
                purge(documents);
            } else {
                untrack(documents);
            }
        } else if (selected instanceof DocEntry(DocumentRecord d)) {
            if (code == KeyCode.F3 && !e.isShortcutDown() && !e.isAltDown() && !e.isShiftDown()) {
                actions.preview(d);
            } else if (code == KeyCode.C && e.isShortcutDown() && e.isShiftDown()) {
                actions.copyContent(d);
            } else if (code == KeyCode.C && e.isShortcutDown()) {
                actions.copyPath(d);
            } else {
                return;
            }
        } else if (selected instanceof AssetEntry(BinaryAsset a)) {
            if (code == KeyCode.F3 && !e.isShortcutDown() && !e.isAltDown() && !e.isShiftDown()) {
                actions.openAsset(a); // no in-app preview for media: the OS application is the preview
            } else if (code == KeyCode.C && e.isShortcutDown() && !e.isShiftDown()) {
                actions.copyAssetPath(a);
            } else if (code == KeyCode.DELETE && !e.isShortcutDown() && !e.isShiftDown()) {
                actions.untrackAsset(a);
            } else {
                return;
            }
        } else if (selected instanceof FolderEntry(FolderIndex.Folder f)
                && code == KeyCode.C && e.isShortcutDown() && !e.isShiftDown()) {
            actions.copyFolderPath(f.path());
        } else {
            return;
        }
        e.consume();
    }

    private void activate(Entry entry) {
        switch (entry) {
            case FolderEntry(FolderIndex.Folder f) -> navigate(new InFolder(f.path()));
            case DocEntry(DocumentRecord d) -> actions.open(d);
            case AssetEntry(BinaryAsset a) -> actions.openAsset(a);
        }
    }

    // ================================================================== context menus

    private void buildMenus() {
        docOpen = docItem("Dosyayı Aç  (Enter)", actions::open);
        docReveal = docItem("Klasörde Göster", actions::reveal);
        MenuItem preview = docItem("Önizle  (Space / F3)", actions::preview);
        docGoToFolder = docItem("Bulunduğu Klasöre Git", d -> navigate(new InFolder(parentOf(d))));
        MenuItem copyPath = docItem("Yolu Kopyala  (Ctrl+C)", actions::copyPath);
        MenuItem copyContent = docItem("İçeriği Kopyala  (Ctrl+Shift+C)", actions::copyContent);
        docUntrack = new MenuItem();
        docUntrack.setOnAction(e -> untrack(menuBatch));
        docPurge = new MenuItem();
        docPurge.setOnAction(e -> purge(menuBatch));
        docPurge.getStyleClass().add("danger-item");
        docMenu.getItems().addAll(docOpen, docReveal, preview, docGoToFolder, new SeparatorMenuItem(), copyPath,
                copyContent, new SeparatorMenuItem(), docUntrack, docPurge);

        MenuItem open = folderItem("Aç", p -> navigate(new InFolder(p)));
        MenuItem reveal = folderItem("Dosya Gezgininde Göster", actions::revealFolder);
        MenuItem copy = folderItem("Yolu Kopyala", actions::copyFolderPath);
        MenuItem rescan = folderItem("Yeniden Tara", actions::rescanFolder);
        MenuItem rename = folderItem("Yeniden Adlandır…", actions::renameFolder);
        folderMenu.getItems().addAll(open, reveal, copy, new SeparatorMenuItem(), rescan, rename);

        MenuItem assetOpen = assetItem("Dosyayı Aç  (Enter)", actions::openAsset);
        MenuItem assetReveal = assetItem("Klasörde Göster", actions::revealAsset);
        assetGoToFolder = assetItem("Bulunduğu Klasöre Git", a -> navigate(new InFolder(parentOf(a.source()))));
        MenuItem assetCopy = assetItem("Yolu Kopyala  (Ctrl+C)", actions::copyAssetPath);
        MenuItem assetUntrack = assetItem("Listeden Kaldır  (Delete)", actions::untrackAsset);
        assetMenu.getItems().addAll(assetOpen, assetReveal, assetGoToFolder, new SeparatorMenuItem(), assetCopy,
                new SeparatorMenuItem(), assetUntrack);
    }

    private MenuItem assetItem(String text, Consumer<BinaryAsset> action) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(e -> {
            if (menuTarget instanceof AssetEntry(BinaryAsset a)) {
                action.accept(a);
            }
        });
        return item;
    }

    private MenuItem docItem(String text, Consumer<DocumentRecord> action) {
        // Keys are labels only: a real accelerator would be registered on the scene and fire with a stale target.
        MenuItem item = new MenuItem(text);
        item.setOnAction(e -> {
            if (menuTarget instanceof DocEntry(DocumentRecord d)) {
                action.accept(d);
            }
        });
        return item;
    }

    private MenuItem folderItem(String text, Consumer<Path> action) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(e -> {
            if (menuTarget instanceof FolderEntry(FolderIndex.Folder f)) {
                action.accept(f.path());
            }
        });
        return item;
    }

    private ContextMenu prepareMenu(Entry entry, TableView<Entry> table) {
        menuTarget = entry;
        menuBatch = batchFor(table, entry);
        if (entry instanceof DocEntry(DocumentRecord d)) {
            int n = menuBatch.size();
            String count = n > 1 ? " — " + n + " belge" : "";
            docUntrack.setText("İndeksten Kaldır" + count + "  (Delete)");
            docPurge.setText("Diskten Tamamen Sil…" + count + "  (Shift+Delete)");
            // Peer documents have no local file to open, reveal or delete.
            docOpen.setDisable(!d.local());
            docReveal.setDisable(!d.local());
            docPurge.setDisable(menuBatch.stream().noneMatch(DocumentRecord::local));
            Path parent = parentOf(d);
            docGoToFolder.setDisable(place instanceof InFolder(Path p) && p.equals(parent));
            return docMenu;
        }
        if (entry instanceof AssetEntry(BinaryAsset a)) {
            Path parent = parentOf(a.source());
            assetGoToFolder.setDisable(place instanceof InFolder(Path p) && p.equals(parent));
            return assetMenu;
        }
        return folderMenu;
    }

    private void showMenu(Entry entry, TableView<Entry> table, Node anchor, double screenX, double screenY) {
        prepareMenu(entry, table).show(anchor, screenX, screenY);
    }

    private void showMenu(Entry entry, Node anchor, double screenX, double screenY) {
        showMenu(entry, null, anchor, screenX, screenY);
    }

    private void showMenu(Entry entry, TableView<Entry> table, Side side, double dx, double dy) {
        prepareMenu(entry, table).show(table, side, dx, dy);
    }

    private void showMenu(Entry entry, TableView<Entry> table, Node anchor, Side side, double dx, double dy) {
        prepareMenu(entry, table).show(anchor, side, dx, dy);
    }

    private static Path parentOf(DocumentRecord d) {
        return parentOf(d.source());
    }

    private static Path parentOf(Path source) {
        Path abs = source.toAbsolutePath().normalize();
        return abs.getParent() == null ? abs : abs.getParent();
    }

    // ================================================================== name cell

    private final class NameCell extends TableCell<Entry, Entry> {
        private final boolean withLocation;
        private final Region folderIcon = new Region();
        private final Label badge = new Label();
        private final StackPane icon = new StackPane();
        private final Label name = new Label();
        private final Label location = new Label();
        private final VBox text = new VBox(1, name);
        private final Button openButton = rowButton("↗", "Aç");
        private final Button moreButton = rowButton("⋯", "Diğer işlemler");
        private final HBox quick = new HBox(2, openButton, moreButton);
        private final HBox row;
        private String badgeStyle;

        NameCell(boolean withLocation) {
            this.withLocation = withLocation;
            folderIcon.getStyleClass().add("folder-glyph");
            badge.getStyleClass().add("type-badge");
            badge.setMinWidth(USE_PREF_SIZE);
            icon.setMinWidth(38);
            icon.setAlignment(Pos.CENTER_LEFT);
            name.getStyleClass().add("doc-name");
            name.setMinWidth(0);
            location.getStyleClass().add("doc-meta");
            location.setMinWidth(0);
            if (withLocation) {
                text.getChildren().add(location);
            }
            text.setMinWidth(0);
            text.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(text, Priority.ALWAYS);
            quick.getStyleClass().add("row-actions");
            quick.setAlignment(Pos.CENTER_RIGHT);
            quick.setMinWidth(USE_PREF_SIZE);
            openButton.setOnAction(e -> withItem(WorkspaceBrowserView.this::activate));
            moreButton.setOnAction(e -> withItem(entry -> showMenu(entry, getTableView(), moreButton, Side.BOTTOM,
                    0, 0)));
            row = new HBox(8, icon, text, quick);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setMinWidth(0);
        }

        private void withItem(Consumer<Entry> action) {
            Entry entry = getItem();
            if (entry != null && !isEmpty()) {
                selectForAction(getTableView(), getIndex());
                action.accept(entry);
            }
        }

        @Override
        protected void updateItem(Entry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) {
                setGraphic(null);
                return;
            }
            switch (entry) {
                case FolderEntry(FolderIndex.Folder f) -> {
                    icon.getChildren().setAll(folderIcon);
                    name.setText(title(f));
                    location.setText(f.documents() + " belge");
                    openButton.setDisable(false);
                    openButton.getTooltip().setText("Klasörü aç");
                }
                case DocEntry(DocumentRecord d) -> {
                    String ext = d.type().extension().toLowerCase(Locale.ROOT);
                    badge.setText(ext.toUpperCase(Locale.ROOT));
                    String style = "type-" + ext;
                    if (!style.equals(badgeStyle)) {
                        if (badgeStyle != null) {
                            badge.getStyleClass().remove(badgeStyle);
                        }
                        badge.getStyleClass().add(style);
                        badgeStyle = style;
                    }
                    icon.getChildren().setAll(badge);
                    name.setText(d.fileName());
                    if (withLocation) {
                        location.setText(locationOf(d));
                    }
                    openButton.setDisable(!d.local());
                    openButton.getTooltip().setText("Dosyayı aç");
                }
                case AssetEntry(BinaryAsset a) -> {
                    badge.setText(MediaCategory.of(a.fileName()).badge);
                    String style = "type-media";
                    if (!style.equals(badgeStyle)) {
                        if (badgeStyle != null) {
                            badge.getStyleClass().remove(badgeStyle);
                        }
                        badge.getStyleClass().add(style);
                        badgeStyle = style;
                    }
                    icon.getChildren().setAll(badge);
                    name.setText(a.fileName());
                    if (withLocation) {
                        location.setText(locationOf(parentOf(a.source())));
                    }
                    openButton.setDisable(false);
                    openButton.getTooltip().setText("Varsayılan uygulamada aç");
                }
            }
            setGraphic(row);
        }
    }

    private static Button rowButton(String glyph, String tooltip) {
        Button b = new Button(glyph);
        b.getStyleClass().add("row-action-button");
        b.setFocusTraversable(false);
        b.setTooltip(new Tooltip(tooltip));
        return b;
    }

    /** "CMP3005 › hafta4" for an indexed folder; the raw parent path otherwise. */
    private String locationOf(DocumentRecord d) {
        return locationOf(parentOf(d));
    }

    private String locationOf(Path parent) {
        List<FolderIndex.Folder> trail = index.trail(parent);
        if (trail.isEmpty()) {
            return parent.toString();
        }
        StringBuilder sb = new StringBuilder();
        for (FolderIndex.Folder f : trail) {
            sb.append(sb.isEmpty() ? "" : "  ›  ").append(title(f));
        }
        return sb.toString();
    }

    // ================================================================== drop target

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
        setOnDragDropped(e -> {
            boolean accepted = e.getDragboard().hasFiles();
            if (accepted) {
                List<Path> paths = new ArrayList<>();
                for (File f : e.getDragboard().getFiles()) {
                    paths.add(f.toPath().toAbsolutePath().normalize());
                }
                actions.ingest(paths);
            }
            getStyleClass().remove("drop-active");
            e.setDropCompleted(accepted);
            e.consume();
        });
    }

    // ================================================================== navigation

    public void navigate(Place target) {
        Objects.requireNonNull(target, "target must not be null");
        Place resolved = resolve(target);
        if (resolved.equals(place)) {
            show();
            return;
        }
        if (resolved instanceof Results) {
            // One results view at a time: an older search in the history would show today's results.
            backStack.removeIf(p -> p instanceof Results);
            forwardStack.removeIf(p -> p instanceof Results);
            if (place instanceof Results) {
                place = resolved;
                show();
                return;
            }
        }
        backStack.push(place);
        while (backStack.size() > HISTORY) {
            backStack.removeLast();
        }
        forwardStack.clear();
        place = resolved;
        show();
    }

    /** Forgets the history and returns to Home (a project switch: the old folders mean nothing any more). */
    public void reset() {
        backStack.clear();
        forwardStack.clear();
        place = HOME;
        show();
    }

    public void back() {
        if (backStack.isEmpty()) {
            return;
        }
        forwardStack.push(place);
        place = resolve(backStack.pop());
        show();
    }

    public void forward() {
        if (forwardStack.isEmpty()) {
            return;
        }
        backStack.push(place);
        place = resolve(forwardStack.pop());
        show();
    }

    public void up() {
        upTarget().ifPresent(this::navigate);
    }

    public boolean canGoBack() {
        return !backStack.isEmpty();
    }

    public boolean canGoForward() {
        return !forwardStack.isEmpty();
    }

    public boolean canGoUp() {
        return upTarget().isPresent();
    }

    private Optional<Place> upTarget() {
        return switch (place) {
            case Home h -> Optional.empty();
            case Results r -> Optional.of(HOME);
            case InFolder(Path p) -> Optional.of(index.isRoot(p) || p.getParent() == null
                    ? HOME : new InFolder(p.getParent()));
        };
    }

    /** A folder that is no longer indexed falls back to its nearest indexed ancestor, then to Home. */
    private Place resolve(Place target) {
        if (target instanceof InFolder(Path p)) {
            for (Path f = p; f != null; f = f.getParent()) {
                if (index.folder(f).isPresent()) {
                    return f.equals(p) ? target : new InFolder(f);
                }
            }
            return HOME;
        }
        return target;
    }

    public Place place() {
        return place;
    }

    /** The folder shown in the explorer, or {@code null} on Home and in the results view. */
    public Path currentFolder() {
        return place instanceof InFolder(Path p) ? p : null;
    }

    public void setOnPlaceChanged(Runnable listener) {
        this.onPlaceChanged = Objects.requireNonNull(listener);
    }

    private void show() {
        Node layer = switch (place) {
            case Home h -> homeView;
            case InFolder f -> {
                fillFolder();
                yield explorerView;
            }
            case Results r -> resultsSlot;
        };
        if (getChildren().size() != 1 || getChildren().getFirst() != layer) {
            getChildren().setAll(layer);
        }
        onPlaceChanged.run();
    }

    private void fillFolder() {
        if (!(place instanceof InFolder(Path p))) {
            return;
        }
        Entry selected = folderTable.getSelectionModel().getSelectedItem();
        List<Entry> rows = new ArrayList<>();
        for (FolderIndex.Folder f : index.subfolders(p)) {
            rows.add(new FolderEntry(f));
        }
        for (DocumentRecord d : index.documents(p)) {
            rows.add(new DocEntry(d));
        }
        for (BinaryAsset a : index.assets(p)) {
            rows.add(new AssetEntry(a));
        }
        folderRows.setAll(rows);
        if (folderTable.getSortOrder().isEmpty()) {
            FXCollections.sort(folderRows, FOLDERS_FIRST.thenComparing(nameOrder()));
        } else {
            folderTable.sort();
        }
        reselect(folderTable, selected);
        folderTable.scrollTo(Math.max(0, folderTable.getSelectionModel().getSelectedIndex()));
    }

    private static void reselect(TableView<Entry> table, Entry previous) {
        if (previous == null) {
            return;
        }
        List<Entry> items = table.getItems();
        for (int i = 0; i < items.size(); i++) {
            if (sameEntry(items.get(i), previous)) {
                table.getSelectionModel().clearAndSelect(i);
                return;
            }
        }
    }

    private static boolean sameEntry(Entry a, Entry b) {
        if (a instanceof FolderEntry(FolderIndex.Folder x) && b instanceof FolderEntry(FolderIndex.Folder y)) {
            return x.path().equals(y.path());
        }
        if (a instanceof DocEntry(DocumentRecord x) && b instanceof DocEntry(DocumentRecord y)) {
            return x.sha256().equals(y.sha256());
        }
        if (a instanceof AssetEntry(BinaryAsset x) && b instanceof AssetEntry(BinaryAsset y)) {
            return x.sha256().equals(y.sha256());
        }
        return false;
    }

    // ================================================================== content

    /** Replaces the folder model and the custom card titles; the current place is kept when it still exists. */
    public void setIndex(FolderIndex next, Map<Path, String> customTitles) {
        this.index = Objects.requireNonNull(next);
        this.titles = Map.copyOf(customTitles);
        rebuildTiles();
        Entry selected = recentTable.getSelectionModel().getSelectedItem();
        List<DocumentRecord> recent = next.recent();
        List<BinaryAsset> recentAssets = next.recentAssets();
        List<Entry> rows = new ArrayList<>(Math.min(recent.size() + recentAssets.size(), RECENT_LIMIT));
        // Merge the two newest-first lists, so a video added today sits above yesterday's PDF.
        int di = 0;
        int ai = 0;
        while (rows.size() < RECENT_LIMIT && (di < recent.size() || ai < recentAssets.size())) {
            boolean takeDoc = ai >= recentAssets.size() || (di < recent.size()
                    && !recent.get(di).ingestedAt().isBefore(recentAssets.get(ai).registeredAt()));
            rows.add(takeDoc ? new DocEntry(recent.get(di++)) : new AssetEntry(recentAssets.get(ai++)));
        }
        recentRows.setAll(rows);
        if (!recentTable.getSortOrder().isEmpty()) {
            recentTable.sort();
        }
        reselect(recentTable, selected);
        recentHeader.setText("˅  En son eklenenler" + (recent.isEmpty() ? "" : "   ·   " + recent.size() + " belge")
                + (recentAssets.isEmpty() ? "" : "   ·   " + recentAssets.size() + " medya"));
        place = resolve(place);
        show();
    }

    /** The display title of a folder: the custom one if set, the folder name otherwise. */
    String title(FolderIndex.Folder folder) {
        return titles.getOrDefault(folder.path(), folder.name());
    }

    FolderIndex index() {
        return index;
    }

    /** Where the chassis mounts the result list (outside focus mode). */
    public StackPane resultsSlot() {
        return resultsSlot;
    }

    /** Items in the current layer: all documents on Home, the rows of the open folder, {@code -1} for results. */
    public int itemCount() {
        return switch (place) {
            case Home h -> index.documentCount() + index.assetCount();
            case InFolder f -> folderRows.size();
            case Results r -> -1;
        };
    }

    /** Whether {@code node} lies inside one of the tables (the chassis routes Space from there to Quick Look). */
    public boolean isInTable(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == recentTable || n == folderTable) {
                return true;
            }
        }
        return false;
    }

    /** The selected document in the visible table, if a document (not a folder) is selected. */
    public Optional<DocumentRecord> selectedDocument() {
        TableView<Entry> table = visibleTable();
        Entry e = table == null ? null : table.getSelectionModel().getSelectedItem();
        return e instanceof DocEntry(DocumentRecord d) ? Optional.of(d) : Optional.empty();
    }

    /** The selected binary asset in the visible table, if one is selected. */
    public Optional<BinaryAsset> selectedAsset() {
        TableView<Entry> table = visibleTable();
        Entry e = table == null ? null : table.getSelectionModel().getSelectedItem();
        return e instanceof AssetEntry(BinaryAsset a) ? Optional.of(a) : Optional.empty();
    }

    private TableView<Entry> visibleTable() {
        return switch (place) {
            case Home h -> recentTable;
            case InFolder f -> folderTable;
            case Results r -> null;
        };
    }

    /** Moves keyboard focus into the visible table (selecting the first row if nothing is selected). */
    public void focusContent() {
        TableView<Entry> table = visibleTable();
        if (table == null) {
            return;
        }
        table.requestFocus();
        if (table.getSelectionModel().getSelectedIndex() < 0 && !table.getItems().isEmpty()) {
            table.getSelectionModel().selectFirst();
            table.getFocusModel().focus(0);
        }
    }

    /** Sorts the visible table (the toolbar's "Sırala" menu). */
    public void sortBy(SortKey key, boolean ascending) {
        TableView<Entry> table = visibleTable();
        if (table == null) {
            return;
        }
        for (TableColumn<Entry, ?> c : table.getColumns()) {
            if (c.getUserData() == key) {
                c.setSortType(ascending ? TableColumn.SortType.ASCENDING : TableColumn.SortType.DESCENDING);
                table.getSortOrder().setAll(List.of(c));
                table.sort();
                return;
            }
        }
    }

    /** The visible table's primary sort, for the "Sırala" menu's check marks; empty when unsorted. */
    public Optional<Map.Entry<SortKey, Boolean>> sortState() {
        TableView<Entry> table = visibleTable();
        if (table == null || table.getSortOrder().isEmpty()) {
            return Optional.empty();
        }
        TableColumn<Entry, ?> c = table.getSortOrder().getFirst();
        return c.getUserData() instanceof SortKey key
                ? Optional.of(Map.entry(key, c.getSortType() == TableColumn.SortType.ASCENDING))
                : Optional.empty();
    }
}
