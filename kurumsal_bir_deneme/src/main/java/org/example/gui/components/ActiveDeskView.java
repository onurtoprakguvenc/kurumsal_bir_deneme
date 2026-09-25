package org.example.gui.components;

import org.example.model.DocumentRecord;

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
import javafx.scene.control.SelectionMode;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Left panel: the "Active Desk".
 *
 * <p>Everything indexed in this session is listed here with its type badge, size and chunk/page counts. Files are
 * added by dropping them on the panel (or through the file chooser button); the context menu hands a document to
 * its native application or untracks it. The panel is a pure view: it never touches the index, the watcher or the
 * file system, it only reports gestures through {@link Actions}. Every mutator must be called on the JavaFX
 * application thread.</p>
 */
public final class ActiveDeskView extends BorderPane {

    /** Gestures the desk reports to the controller. */
    public interface Actions {

        /** Files (or directories) were dropped on the desk. */
        void ingest(List<File> files);

        /** The "add files" button was pressed. */
        void chooseFiles();

        /** Open this document in its native operating-system application. */
        void open(DocumentRecord document);

        /** Drop this document from the index and the catalog; the file stays on disk. */
        void untrack(DocumentRecord document);

        /** The live-watch toggle was switched. */
        void watchToggled(boolean enabled);
    }

    private static final Locale TR = Locale.of("tr", "TR");

    private final Actions actions;
    private final ObservableList<DocumentRecord> documents = FXCollections.observableArrayList();
    private final ListView<DocumentRecord> list = new ListView<>(documents);
    private final ToggleButton watchToggle = new ToggleButton("Canlı izleme");
    private final Label summary = new Label("0 belge");
    private boolean suppressWatchEvent;

    public ActiveDeskView(Actions actions) {
        this.actions = actions;
        getStyleClass().add("active-desk");

        Label title = new Label("Aktif Masa");
        title.getStyleClass().add("panel-title");

        Button add = new Button("+ Dosya");
        add.getStyleClass().add("ghost-button");
        add.setTooltip(new Tooltip("PDF, DOCX, XLSX, PPTX, CSV veya TXT dosyası ekle"));
        add.setOnAction(event -> actions.chooseFiles());

        watchToggle.getStyleClass().add("watch-toggle");
        watchToggle.setTooltip(new Tooltip("Diskteki değişiklikleri izle ve otomatik yeniden indeksle"));
        watchToggle.setOnAction(event -> {
            if (!suppressWatchEvent) {
                actions.watchToggled(watchToggle.isSelected());
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, title, spacer, add);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("panel-header");

        summary.getStyleClass().add("panel-subtitle");
        VBox top = new VBox(6, header, summary);
        top.setPadding(new Insets(14, 14, 10, 14));
        setTop(top);

        Label placeholder = new Label("Belge yok.\nDosyaları buraya sürükleyip bırakın.");
        placeholder.getStyleClass().add("placeholder");
        placeholder.setWrapText(true);
        list.setPlaceholder(placeholder);
        list.getStyleClass().add("document-list");
        list.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        list.setCellFactory(view -> new DocumentCell());
        setCenter(list);

        Label dropHint = new Label("Sürükle bırak: PDF · DOCX · XLSX · PPTX · CSV · TXT");
        dropHint.getStyleClass().add("drop-hint");
        dropHint.setWrapText(true);
        HBox footer = new HBox(8, dropHint, spacerRegion(), watchToggle);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(10, 14, 12, 14));
        footer.getStyleClass().add("panel-footer");
        setBottom(footer);

        installDropTarget();
    }

    private static Region spacerRegion() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    /** Accepts file drops anywhere on the panel; the ingestion itself happens off the UI thread. */
    private void installDropTarget() {
        setOnDragOver(event -> {
            Dragboard board = event.getDragboard();
            if (board.hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        setOnDragEntered(event -> {
            if (event.getDragboard().hasFiles()) {
                getStyleClass().add("drop-active");
            }
            event.consume();
        });
        setOnDragExited(event -> {
            getStyleClass().remove("drop-active");
            event.consume();
        });
        setOnDragDropped(event -> {
            Dragboard board = event.getDragboard();
            boolean accepted = board.hasFiles();
            if (accepted) {
                actions.ingest(List.copyOf(board.getFiles()));
            }
            getStyleClass().remove("drop-active");
            event.setDropCompleted(accepted);
            event.consume();
        });
    }

    // ------------------------------------------------------------------ view state (FX thread only)

    /** Replaces the listed documents and refreshes the counters. */
    public void setDocuments(List<DocumentRecord> records) {
        DocumentRecord selected = list.getSelectionModel().getSelectedItem();
        documents.setAll(records);
        long chunks = records.stream().mapToLong(d -> d.chunks().size()).sum();
        long bytes = records.stream().mapToLong(DocumentRecord::sizeBytes).sum();
        summary.setText(records.isEmpty()
                ? "0 belge"
                : String.format(TR, "%d belge · %,d parça · %s", records.size(), chunks, size(bytes)));
        if (selected != null) {
            records.stream()
                    .filter(d -> d.sha256().equals(selected.sha256()))
                    .findFirst()
                    .ifPresent(d -> list.getSelectionModel().select(d));
        }
    }

    /** Moves the selection to a document (used when a citation is revealed). */
    public void reveal(String sha256) {
        for (int i = 0; i < documents.size(); i++) {
            if (documents.get(i).sha256().equals(sha256)) {
                list.getSelectionModel().select(i);
                list.scrollTo(i);
                return;
            }
        }
    }

    /** Reflects the watcher state without firing {@link Actions#watchToggled(boolean)}. */
    public void setWatchEnabled(boolean enabled) {
        suppressWatchEvent = true;
        watchToggle.setSelected(enabled);
        watchToggle.setText(enabled ? "Canlı izleme: açık" : "Canlı izleme");
        suppressWatchEvent = false;
    }

    public DocumentRecord selected() {
        return list.getSelectionModel().getSelectedItem();
    }

    // ------------------------------------------------------------------ cell

    private final class DocumentCell extends ListCell<DocumentRecord> {

        private final Label badge = new Label();
        private final Label name = new Label();
        private final Label meta = new Label();
        private final HBox head = new HBox(8, badge, name);
        private final VBox body = new VBox(3, head, meta);
        private final ContextMenu menu = new ContextMenu();

        DocumentCell() {
            badge.getStyleClass().add("type-badge");
            name.getStyleClass().add("doc-name");
            meta.getStyleClass().add("doc-meta");
            head.setAlignment(Pos.CENTER_LEFT);
            body.getStyleClass().add("doc-cell");
            getStyleClass().add("document-cell");

            MenuItem open = new MenuItem("Sistem uygulamasında aç");
            open.setOnAction(event -> {
                DocumentRecord item = getItem();
                if (item != null) {
                    actions.open(item);
                }
            });
            MenuItem untrack = new MenuItem("İndeksten çıkar (dosya diskte kalır)");
            untrack.setOnAction(event -> {
                DocumentRecord item = getItem();
                if (item != null) {
                    actions.untrack(item);
                }
            });
            menu.getItems().addAll(open, untrack);
        }

        @Override
        protected void updateItem(DocumentRecord document, boolean empty) {
            super.updateItem(document, empty);
            if (empty || document == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                return;
            }
            badge.setText(document.type().name());
            name.setText(document.fileName());
            meta.setText(describe(document));
            setText(null);
            setGraphic(body);
            setContextMenu(menu);
        }
    }

    private static String describe(DocumentRecord document) {
        StringBuilder sb = new StringBuilder(64);
        sb.append(size(document.sizeBytes()));
        if (document.pageCount() > 0) {
            sb.append(" · ").append(document.pageCount()).append(pageWord(document));
        }
        sb.append(String.format(TR, " · %,d parça", document.chunks().size()));
        if (!document.local()) {
            sb.append(" · ").append(document.origin());
        }
        if (document.emptyPages() > 0) {
            sb.append(" · ").append(document.emptyPages()).append(" boş sayfa");
        }
        return sb.toString();
    }

    private static String pageWord(DocumentRecord document) {
        return switch (document.type()) {
            case PPTX, PPT -> " slayt";
            case XLSX -> " sayfa";
            default -> " sayfa";
        };
    }

    private static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(TR, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(TR, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(TR, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
