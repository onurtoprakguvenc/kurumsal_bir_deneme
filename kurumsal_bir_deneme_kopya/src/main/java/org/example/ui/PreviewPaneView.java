package org.example.ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.example.preview.PreviewDocument;
import org.example.model.DocumentType;
import org.example.preview.PreviewDocument.PreviewLine;
import org.example.preview.PreviewResult;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Structured, memory-bounded preview card, shown centred by {@link QuickLookOverlay}.
 *
 * <p>A {@link PreviewResult.Ready} document is shown as a virtualized list of its structured lines (text, table rows,
 * section markers, notices) — the list references the lines the {@link PreviewDocument} already holds, which the
 * service capped at its line/char budget. Every other outcome, above all {@link PreviewResult.TooLarge}, is rendered
 * as a single explanatory message: nothing was parsed, so there is nothing else to show. The header carries the file
 * name, a metadata line and a page / slide indicator that follows the focused line.</p>
 *
 * <p>{@link #clear()} drops every reference the pane holds; the chassis calls it together with
 * {@code PreviewService#dismiss()} whenever the card is closed, so the document becomes collectable immediately.</p>
 */
public final class PreviewPaneView extends VBox {

    private final Label title = new Label("ÖNİZLEME");
    private final Label meta = new Label();
    private final Label position = new Label();
    private final Label message = new Label();
    private final ObservableList<PreviewLine> items = FXCollections.observableArrayList();
    private final ListView<PreviewLine> lines = new ListView<>(items);
    private Runnable onClose = () -> { };
    private Runnable onOpen = () -> { };
    private Path shown;
    private DocumentType shownType;
    private int shownPages;

    public PreviewPaneView() {
        getStyleClass().addAll("context-pane", "preview-pane");
        setMinWidth(220);

        title.getStyleClass().add("panel-title");
        title.setMinWidth(0);
        meta.getStyleClass().add("doc-meta");
        position.getStyleClass().add("preview-position");
        position.setMinWidth(USE_PREF_SIZE);
        Button open = new Button("Dosyayı Aç");
        open.getStyleClass().add("ghost-button");
        open.setFocusTraversable(false);
        open.setTooltip(new Tooltip("Varsayılan uygulamada aç"));
        open.setOnAction(e -> onOpen.run());
        Button close = new Button("✕");
        close.getStyleClass().addAll("ghost-button", "preview-close");
        close.setFocusTraversable(false);
        close.setTooltip(new Tooltip("Kapat (Esc / Space)"));
        close.setOnAction(e -> onClose.run());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(8, title, spacer, position, open, close);
        top.setAlignment(Pos.CENTER_LEFT);
        VBox header = new VBox(2, top, meta);
        header.getStyleClass().add("panel-header");
        header.setPadding(new Insets(12, 12, 10, 18));

        message.getStyleClass().add("preview-message");
        message.setWrapText(true);
        message.setPadding(new Insets(12, 14, 12, 14));
        message.setMaxWidth(Double.MAX_VALUE);

        lines.getStyleClass().add("preview-list");
        lines.setCellFactory(lv -> new LineCell());
        VBox.setVgrow(lines, Priority.ALWAYS);
        lines.getFocusModel().focusedIndexProperty().addListener((obs, old, index) -> updatePosition(index.intValue()));

        setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                onClose.run();
                e.consume();
            }
        });
        getChildren().add(header);
    }

    public void setOnClose(Runnable action) {
        this.onClose = Objects.requireNonNull(action);
    }

    /** "Dosyayı Aç": opens {@link #shownPath()} with the default application. */
    public void setOnOpen(Runnable action) {
        this.onOpen = Objects.requireNonNull(action);
    }

    /** Moves keyboard focus into the card: the line list when a document is shown, the card itself otherwise. */
    public void focusBody() {
        if (getChildren().contains(lines)) {
            lines.requestFocus();
        } else {
            requestFocus();
        }
    }

    /** Path currently shown or being loaded, or {@code null}. */
    public Path shownPath() {
        return shown;
    }

    /** Placeholder while the service extracts on its virtual thread. */
    public void showLoading(Path path) {
        shown = path;
        resetPosition();
        title.setText(name(path));
        meta.setText("yükleniyor…");
        showMessage("Önizleme hazırlanıyor…", "preview-loading");
    }

    /** Renders {@code result}; {@link PreviewResult.Superseded} is ignored (a newer request owns the pane). */
    public void show(PreviewResult result) {
        switch (result) {
            case PreviewResult.Ready(Path path, PreviewDocument doc) -> {
                if (doc.closed()) {
                    return; // replaced by a newer preview between completion and this pulse
                }
                shown = path;
                shownType = doc.type();
                shownPages = doc.pages();
                title.setText(name(path));
                meta.setText(describe(doc));
                try {
                    items.setAll(doc.lines(0, doc.lineCount()));
                } catch (IllegalStateException closedMeanwhile) {
                    return;
                }
                boolean hadFocus = isFocused() || lines.isFocused();
                mount(lines);
                lines.scrollTo(0);
                lines.getFocusModel().focus(0);
                updatePosition(0);
                if (hadFocus) {
                    lines.requestFocus(); // the loading placeholder held focus; keep arrows working
                }
            }
            case PreviewResult.TooLarge t -> {
                shown = t.path();
                resetPosition();
                title.setText(name(t.path()));
                meta.setText(t.type().extension().toUpperCase(Locale.ROOT) + " · " + human(t.sizeBytes()));
                showMessage(String.format(Locale.ROOT,
                        "Bu dosya satır içi önizleme için çok büyük: %s (sınır %s, %s).%n"
                                + "Bellek korunması için ayrıştırılmadı — \"Dosyayı Aç\" ile varsayılan uygulamada açın.",
                        human(t.sizeBytes()), human(t.limitBytes()), t.type().extension().toUpperCase(Locale.ROOT)),
                        "preview-too-large");
            }
            case PreviewResult.Unsupported u -> {
                shown = u.path();
                resetPosition();
                title.setText(name(u.path()));
                meta.setText("");
                showMessage("Bu dosya türü için önizleme yok: " + u.reason(), "preview-notice");
            }
            case PreviewResult.Failed f -> {
                shown = f.path();
                resetPosition();
                title.setText(name(f.path()));
                meta.setText(f.reason().name());
                showMessage("Önizleme başarısız: " + f.message(), "preview-failed");
            }
            case PreviewResult.Superseded s -> {
                // a newer request replaced this one; its own result will arrive
            }
        }
    }

    /** Releases every line reference and resets to an empty pane. */
    public void clear() {
        items.clear();
        shown = null;
        resetPosition();
        title.setText("ÖNİZLEME");
        meta.setText("");
        getChildren().retainAll(getChildren().getFirst());
    }

    private void showMessage(String text, String style) {
        items.clear();
        message.getStyleClass().setAll("label", "preview-message", style);
        message.setText(text);
        mount(message);
    }

    /** Keeps exactly one body node under the header in the layout tree. */
    private void mount(Region body) {
        if (getChildren().size() == 2 && getChildren().get(1) == body) {
            return;
        }
        getChildren().retainAll(getChildren().getFirst());
        getChildren().add(body);
    }

    private void resetPosition() {
        shownType = null;
        shownPages = 0;
        position.setText("");
    }

    /** "Sayfa 3 / 12" or "Slayt 3 / 12" for the focused line; empty for formats without pages. */
    private void updatePosition(int index) {
        if (index < 0 || index >= items.size() || shownType == null) {
            position.setText("");
            return;
        }
        int page = items.get(index).page();
        if (page <= 0) {
            position.setText("");
            return;
        }
        String unit = shownType == DocumentType.PPT || shownType == DocumentType.PPTX ? "Slayt" : "Sayfa";
        position.setText(unit + " " + page + (shownPages > 0 ? " / " + shownPages : ""));
    }

    private static String describe(PreviewDocument doc) {
        StringBuilder sb = new StringBuilder();
        sb.append(doc.type().extension().toUpperCase(Locale.ROOT)).append(" · ").append(human(doc.sizeBytes()));
        if (doc.pages() > 0) {
            boolean slides = doc.type() == DocumentType.PPT || doc.type() == DocumentType.PPTX;
            sb.append(" · ").append(doc.pages()).append(slides ? " slayt" : " sayfa");
        }
        sb.append(" · ").append(doc.lineCount()).append(" satır");
        sb.append(String.format(Locale.ROOT, " · %.0f ms", doc.extractMillis()));
        if (doc.truncated()) {
            sb.append(" · yalnızca başlangıç");
        }
        return sb.toString();
    }

    private static String name(Path path) {
        return path == null || path.getFileName() == null ? "?" : path.getFileName().toString();
    }

    private static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** One structured line; a single wrapping label whose style class follows the line kind. */
    private static final class LineCell extends ListCell<PreviewLine> {
        private String style;

        LineCell() {
            setWrapText(true);
            setPrefWidth(0);
        }

        @Override
        protected void updateItem(PreviewLine line, boolean empty) {
            super.updateItem(line, empty);
            if (empty || line == null) {
                setText(null);
                restyle(null);
                return;
            }
            switch (line) {
                case PreviewDocument.Text t -> {
                    setText(t.text());
                    restyle("preview-text");
                }
                case PreviewDocument.TableRow r -> {
                    setText(String.join("  │  ", r.cells()));
                    restyle(r.header() ? "preview-table-header" : "preview-table-row");
                }
                case PreviewDocument.Section s -> {
                    setText(s.label());
                    restyle("preview-section");
                }
                case PreviewDocument.Notice n -> {
                    setText(n.text());
                    restyle("preview-notice");
                }
            }
        }

        private void restyle(String next) {
            if (Objects.equals(style, next)) {
                return;
            }
            if (style != null) {
                getStyleClass().remove(style);
            }
            if (next != null) {
                getStyleClass().add(next);
            }
            style = next;
        }
    }

    /** Visible for the chassis' diagnostics: lines currently referenced by the pane. */
    public int retainedLines() {
        return items.size();
    }
}
