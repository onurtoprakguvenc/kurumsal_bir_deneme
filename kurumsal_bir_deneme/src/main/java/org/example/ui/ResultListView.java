package org.example.ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.example.model.Location;
import org.example.model.SearchHit;
import org.example.state.Subscription;
import org.example.state.ViewModeCoordinator;
import org.example.state.ViewModeCoordinator.DetailedView;
import org.example.state.ViewModeCoordinator.MemorySnapshot;
import org.example.state.ViewModeCoordinator.SimpleView;
import org.example.state.ViewModeCoordinator.ViewMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The {@code RESULTS} panel: an optional grounded-evidence card (streamed answer plus its sources) above a virtualized
 * hit list.
 *
 * <p><b>Rendering contract.</b> The list's items are plain row indices into the {@link ViewModeCoordinator}'s reusable
 * result table; the rows themselves are never copied into the UI. A new result only resizes the index list, and a mode
 * switch touches no collection at all — both re-render the live cells in place ({@link HitCell#render()}), so only the
 * handful of visible rows ever cost a projection. Simple rows show the clean name, the highlighted snippet and a
 * relevance badge ({@code [High 100%]}); detailed rows add the BM25 score, term frequencies, chunk coordinate and
 * parse/query latency, with live heap figures in the header.</p>
 *
 * <p><b>Actions.</b> Every row offers the same document actions as the file tree: a right-click menu (open, reveal,
 * preview, copy path, copy content, untrack, delete from disk), quick buttons on the hovered or selected row (open,
 * preview, more…) and keys ({@code Delete}/{@code Backspace} untrack, {@code Shift+Delete} delete from disk,
 * {@code Ctrl+C} copy path, {@code Ctrl+Shift+C} copy content; {@code Enter} and {@code F3} come from the key
 * registry). Rows are handed to the chassis by index; it resolves the document and asks for confirmation.</p>
 */
public final class ResultListView extends VBox {

    /** What the cells and the context menu ask of the chassis. Rows are 0-based. */
    public interface Actions {
        void select(int row);

        void open(int row);

        void reveal(int row);

        void preview(int row);

        void copyPath(int row);

        void copyContent(int row);

        /** Removes the row's document from the index only. */
        void untrack(int row);

        /** Deletes the row's source file from disk (the chassis asks for confirmation first). */
        void purge(int row);

        /** Opens an evidence source by document id (sources of a grounded answer). */
        void openSource(String docId);

        /** True when {@code docId} is a built-in system fixture; such rows carry the demo tag. */
        default boolean isSystemDocument(String docId) {
            return false;
        }
    }

    /** Text of the tag shown next to the file name of a system fixture hit. */
    static final String DEMO_TAG = "Demo Dokümanı";

    /** One chip under a grounded answer. {@code docId} is {@code null} for sources that cannot be opened locally. */
    public record SourceChip(String tag, String name, String coordinate, String docId) {
    }

    private static final int MAX_ANSWER_CHARS = 32_000;

    private final Actions actions;
    private final ObservableList<Integer> rows = FXCollections.observableArrayList();
    private final ListView<Integer> list = new ListView<>(rows);
    private final Set<HitCell> liveCells = Collections.newSetFromMap(new WeakHashMap<>());
    private final Label header = new Label();
    private final Label empty = new Label("Aramak için yukarıya yazın.\nBelgeleri soldaki alana sürükleyip bırakın.");
    private final ContextMenu menu = new ContextMenu();
    private final MenuItem openItem = new MenuItem("Dosyayı Aç");
    private final MenuItem revealItem = new MenuItem("Klasörde Göster");
    private final MenuItem previewItem = new MenuItem("Önizleme (F3)");
    private final MenuItem copyPathItem = new MenuItem("Yolunu Kopyala  (Ctrl+C)");
    private final MenuItem copyContentItem = new MenuItem("İçeriği Kopyala  (Ctrl+Shift+C)");
    private final MenuItem untrackItem = new MenuItem("İndeksten Kaldır  (Delete)");
    private final MenuItem purgeItem = new MenuItem("Diskten Tamamen Sil…  (Shift+Delete)");

    // Evidence card (mounted only while an answer is shown).
    private final VBox answerCard = new VBox(6);
    private final Label answerTitle = new Label();
    private final TextFlow answerFlow = new TextFlow();
    private final Text answerText = new Text();
    private final Text answerNotice = new Text();
    private final FlowPane sourceBar = new FlowPane(6, 6);
    private final ScrollPane answerScroll = new ScrollPane(answerFlow);
    private final StringBuilder pendingAnswer = new StringBuilder();
    private final StringBuilder answer = new StringBuilder();
    private final Fx.Coalescer answerFlush = new Fx.Coalescer(this::flushAnswer);
    private volatile long answerToken;

    private ViewModeCoordinator view;
    private final List<Subscription> subscriptions = new ArrayList<>();
    private long shownGeneration = -1;
    private boolean badgesVisible = true;
    private int menuRow = -1;
    /** Result generation the context menu was opened on; its actions are dropped once the rows changed meaning. */
    private long menuGeneration = -1;

    public ResultListView(Actions actions) {
        this.actions = Objects.requireNonNull(actions);
        getStyleClass().addAll("evidence-stream", "hits-card");
        setPadding(new Insets(10, 0, 0, 0));
        setSpacing(6);

        header.getStyleClass().add("section-title");
        header.setPadding(new Insets(0, 14, 0, 14));
        empty.getStyleClass().add("placeholder");

        list.getStyleClass().add("hit-list");
        list.setPlaceholder(empty);
        list.setCellFactory(lv -> {
            HitCell cell = new HitCell();
            liveCells.add(cell);
            return cell;
        });
        list.getSelectionModel().selectedIndexProperty().addListener((obs, old, row) -> {
            if (row.intValue() >= 0) {
                actions.select(row.intValue());
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);
        list.addEventHandler(KeyEvent.KEY_PRESSED, this::onListKey);

        openItem.setOnAction(e -> {
            if (menuCurrent()) {
                actions.open(menuRow);
            }
        });
        revealItem.setOnAction(e -> {
            if (menuCurrent()) {
                actions.reveal(menuRow);
            }
        });
        previewItem.setOnAction(e -> {
            if (menuCurrent()) {
                actions.preview(menuRow);
            }
        });
        copyPathItem.setOnAction(e -> {
            if (menuCurrent()) {
                actions.copyPath(menuRow);
            }
        });
        copyContentItem.setOnAction(e -> {
            if (menuCurrent()) {
                actions.copyContent(menuRow);
            }
        });
        untrackItem.setOnAction(e -> {
            if (menuCurrent()) {
                actions.untrack(menuRow);
            }
        });
        purgeItem.setOnAction(e -> {
            if (menuCurrent()) {
                actions.purge(menuRow);
            }
        });
        purgeItem.getStyleClass().add("danger-item");
        menu.getItems().addAll(openItem, revealItem, previewItem, new SeparatorMenuItem(), copyPathItem,
                copyContentItem, new SeparatorMenuItem(), untrackItem, purgeItem);

        buildAnswerCard();
        getChildren().addAll(header, list);
        updateHeader();
    }

    // ================================================================== binding

    /** Attaches to a project's view model; detaches from the previous one first. */
    public void bind(ViewModeCoordinator coordinator) {
        unbind();
        this.view = Objects.requireNonNull(coordinator);
        subscriptions.add(coordinator.onModeChanged((previous, current) -> Fx.run(this::renderVisible)));
        subscriptions.add(coordinator.onResultsChanged((generation, size) ->
                Fx.run(() -> applyResults(generation, size))));
        shownGeneration = -1;
        applyResults(coordinator.generation(), coordinator.size());
    }

    public void unbind() {
        subscriptions.forEach(Subscription::close);
        subscriptions.clear();
        view = null;
        rows.clear();
        clearAnswer();
        updateHeader();
    }

    private void applyResults(long generation, int size) {
        if (view == null || generation < shownGeneration) {
            return; // an older publish arriving after a newer one
        }
        shownGeneration = generation;
        int current = rows.size();
        if (size < current) {
            rows.remove(size, current);
        } else {
            for (int i = current; i < size; i++) {
                rows.add(i);
            }
        }
        renderVisible();
        if (size > 0) {
            list.getSelectionModel().clearAndSelect(0);
            list.scrollTo(0);
        } else {
            list.getSelectionModel().clearSelection();
        }
    }

    /** Re-projects every live cell for the current mode and data; the index list is left untouched. */
    public void renderVisible() {
        for (HitCell cell : liveCells) {
            cell.render();
        }
        updateHeader();
    }

    private void updateHeader() {
        ViewModeCoordinator v = view;
        if (v == null || v.size() == 0) {
            header.setText(v == null ? "" : v.query().isBlank() ? "SONUÇLAR" : "SONUÇ YOK");
            return;
        }
        String base = String.format(Locale.ROOT, "%d SONUÇ · %.2f ms", v.size(), v.queryMillis());
        if (v.mode() == ViewMode.DETAILED) {
            MemorySnapshot m = MemorySnapshot.now();
            base += String.format(Locale.ROOT, " · %d eşleşen parça · heap %d/%d MB (%d%%)", v.matchedChunks(),
                    m.usedBytes() >> 20, m.maxBytes() >> 20, m.usedPercent());
        }
        header.setText(base);
    }

    // ================================================================== presentation switches

    /** Relevance badges are chrome: focus mode hides them. */
    public void setBadgesVisible(boolean visible) {
        if (badgesVisible != visible) {
            badgesVisible = visible;
            renderVisible();
        }
    }

    /** Re-labels the preview menu item with the currently bound shortcut. */
    public void setPreviewShortcut(String stroke) {
        previewItem.setText(stroke == null ? "Önizle" : "Önizle  (" + stroke + ")");
    }

    public void setOpenShortcut(String stroke) {
        openItem.setText(stroke == null ? "Dosyayı Aç" : "Dosyayı Aç  (" + stroke + ")");
    }

    public void setRevealShortcut(String stroke) {
        revealItem.setText(stroke == null ? "Klasörde Göster" : "Klasörde Göster  (" + stroke + ")");
    }

    public void focusList() {
        list.requestFocus();
        if (!rows.isEmpty() && list.getSelectionModel().getSelectedIndex() < 0) {
            list.getSelectionModel().clearAndSelect(0);
        }
    }

    public int selectedRow() {
        return list.getSelectionModel().getSelectedIndex();
    }

    private void onListKey(KeyEvent e) {
        int row = selectedRow();
        if (row < 0 || !showingCurrent()) {
            // A newer result set was published but not rendered yet: row N now means another document.
            return;
        }
        KeyCode code = e.getCode();
        if (code == KeyCode.DELETE && e.isShiftDown()) {
            actions.purge(row);
        } else if ((code == KeyCode.DELETE || code == KeyCode.BACK_SPACE) && !e.isShortcutDown()) {
            actions.untrack(row);
        } else if (code == KeyCode.C && e.isShortcutDown() && e.isShiftDown()) {
            actions.copyContent(row);
        } else if (code == KeyCode.C && e.isShortcutDown()) {
            actions.copyPath(row);
        } else if (code == KeyCode.CONTEXT_MENU) {
            prepareMenu(row);
            menu.show(list, javafx.geometry.Side.BOTTOM, 0, 0);
        } else {
            return;
        }
        e.consume();
    }

    private void showMenu(Node anchor, int row, double screenX, double screenY) {
        prepareMenu(row);
        list.getSelectionModel().clearAndSelect(row);
        menu.show(anchor, screenX, screenY);
    }

    /** Targets the menu at {@code row}; a system fixture can be neither untracked nor purged. */
    private void prepareMenu(int row) {
        menuRow = row;
        ViewModeCoordinator v = view;
        menuGeneration = v == null ? -1 : v.generation();
        boolean fixture = isSystemRow(row);
        untrackItem.setDisable(fixture);
        purgeItem.setDisable(fixture);
    }

    /** True when the rows on screen belong to the coordinator's current result set. */
    private boolean showingCurrent() {
        ViewModeCoordinator v = view;
        return v != null && shownGeneration == v.generation();
    }

    private boolean menuCurrent() {
        ViewModeCoordinator v = view;
        return v != null && menuGeneration == v.generation() && showingCurrent();
    }

    private boolean isSystemRow(int row) {
        ViewModeCoordinator v = view;
        return v != null && v.hit(row).map(h -> actions.isSystemDocument(h.docId())).orElse(false);
    }

    // ================================================================== evidence card

    private void buildAnswerCard() {
        answerCard.getStyleClass().add("answer-card");
        answerCard.setPadding(new Insets(0, 14, 6, 14));

        answerTitle.getStyleClass().add("section-title");
        Button close = new Button("✕");
        close.getStyleClass().add("ghost-button");
        close.setOnAction(e -> clearAnswer());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(8, answerTitle, spacer, close);
        top.setAlignment(Pos.CENTER_LEFT);

        answerText.getStyleClass().add("answer-text");
        answerNotice.getStyleClass().add("notice-text");
        answerFlow.getStyleClass().add("answer-flow");
        answerFlow.getChildren().add(answerText);
        answerScroll.getStyleClass().add("answer-scroll");
        answerScroll.setFitToWidth(true);
        answerScroll.setMaxHeight(260);
        answerScroll.setPrefViewportHeight(140);
        sourceBar.getStyleClass().add("source-bar");

        answerCard.getChildren().addAll(top, answerScroll);
    }

    /** Mounts an empty evidence card for a new question; tokens from an older question are dropped from now on. */
    public long beginAnswer(String title) {
        long token = ++answerToken;
        synchronized (pendingAnswer) {
            pendingAnswer.setLength(0);
        }
        answer.setLength(0);
        answerText.setText("");
        answerText.getStyleClass().setAll("placeholder-text");
        answerText.setText("Kanıt aranıyor…");
        answerFlow.getChildren().setAll(answerText);
        answerCard.getChildren().remove(sourceBar);
        sourceBar.getChildren().clear();
        answerTitle.setText(title);
        if (!getChildren().contains(answerCard)) {
            getChildren().addFirst(answerCard);
        }
        return token;
    }

    /** Streamed delta from any thread; coalesced into at most one text update per pulse. */
    public void appendAnswer(long token, String delta) {
        if (token != answerToken || delta == null || delta.isEmpty()) {
            return;
        }
        synchronized (pendingAnswer) {
            if (pendingAnswer.length() < MAX_ANSWER_CHARS) {
                pendingAnswer.append(delta);
            }
        }
        answerFlush.request();
    }

    private void flushAnswer() {
        String delta;
        synchronized (pendingAnswer) {
            if (pendingAnswer.isEmpty()) {
                return;
            }
            delta = pendingAnswer.toString();
            pendingAnswer.setLength(0);
        }
        if (answer.isEmpty()) {
            answerText.getStyleClass().setAll("answer-text");
        }
        int room = MAX_ANSWER_CHARS - answer.length();
        if (room <= 0) {
            return;
        }
        answer.append(delta, 0, Math.min(room, delta.length()));
        answerText.setText(answer.toString());
        answerScroll.setVvalue(1.0);
    }

    /**
     * Completes the card: flushes pending tokens, appends an optional notice ({@code refusal} styles it as an error)
     * and lays out the source chips.
     */
    public void finishAnswer(long token, String notice, boolean refusal, List<SourceChip> sources) {
        Fx.run(() -> {
            if (token != answerToken) {
                return;
            }
            flushAnswer();
            if (answer.isEmpty()) {
                answerText.setText("");
            }
            if (notice != null && !notice.isBlank()) {
                answerNotice.getStyleClass().setAll(refusal ? "refusal-text" : "notice-text");
                answerNotice.setText((answer.isEmpty() ? "" : "\n\n") + notice);
                answerFlow.getChildren().setAll(answerText, answerNotice);
            }
            sourceBar.getChildren().clear();
            for (SourceChip s : sources) {
                sourceBar.getChildren().add(sourceNode(s));
            }
            if (!sources.isEmpty() && !answerCard.getChildren().contains(sourceBar)) {
                answerCard.getChildren().add(sourceBar);
            }
        });
    }

    private Node sourceNode(SourceChip s) {
        Label tag = new Label("[" + s.tag() + "]");
        tag.getStyleClass().add("source-tag");
        Label name = new Label(s.name());
        name.getStyleClass().add("source-name");
        name.setMaxWidth(260);
        HBox chip = new HBox(6, tag, name);
        if (s.coordinate() != null && !s.coordinate().isBlank()) {
            Label coord = new Label(s.coordinate());
            coord.getStyleClass().add("source-coord");
            chip.getChildren().add(coord);
        }
        chip.getStyleClass().add("source-row");
        chip.setAlignment(Pos.CENTER_LEFT);
        if (s.docId() != null) {
            chip.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY) {
                    actions.openSource(s.docId());
                }
            });
        }
        return chip;
    }

    /** Unmounts the evidence card and drops its text. */
    public void clearAnswer() {
        answerToken++;
        synchronized (pendingAnswer) {
            pendingAnswer.setLength(0);
        }
        answer.setLength(0);
        answerText.setText("");
        answerNotice.setText("");
        sourceBar.getChildren().clear();
        getChildren().remove(answerCard);
    }

    // ================================================================== cell

    /** Reusable row cell: one node tree per cell, re-filled on every render. */
    private final class HitCell extends ListCell<Integer> {
        private final Label rank = new Label();
        private final Label name = new Label();
        private final Label badge = new Label();
        private final Label demoTag = new Label(DEMO_TAG);
        private final HBox top;
        private final TextFlow snippet = new TextFlow();
        private final Label coordinates = new Label();
        private final Label telemetry = new Label();
        private final Button openButton = rowButton("↗ Aç", "Varsayılan uygulamayla aç (Enter)");
        private final Button previewButton = rowButton("Önizle", "Satır içi önizleme (F3)");
        private final Button moreButton = rowButton("•••", "Diğer işlemler");
        private final HBox quick = new HBox(4, openButton, previewButton, moreButton);
        private final VBox box;
        private String badgeStyle;

        HitCell() {
            rank.getStyleClass().add("hit-rank");
            rank.setMinWidth(USE_PREF_SIZE);
            name.getStyleClass().add("hit-file");
            name.setMinWidth(0);
            badge.getStyleClass().add("hit-badge");
            badge.setMinWidth(USE_PREF_SIZE);
            // Sits right after the name; the name ellipsizes first, so the tag stays whole on narrow lists.
            demoTag.getStyleClass().add("hit-demo-tag");
            demoTag.setMinWidth(USE_PREF_SIZE);
            demoTag.setTooltip(new Tooltip("Rehberdeki 30 saniyelik tur için gömülü örnek belge; "
                    + "belge listelerinde görünmez ve kaldırılamaz"));
            demoTag.managedProperty().bind(demoTag.visibleProperty());
            demoTag.setVisible(false);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            quick.getStyleClass().add("row-actions");
            quick.setAlignment(Pos.CENTER_RIGHT);
            quick.setMinWidth(USE_PREF_SIZE);
            // Visible on the hovered or selected row only; kept managed so rows never jump in height or width.
            quick.visibleProperty().bind(hoverProperty().or(selectedProperty()));
            openButton.setOnAction(e -> actions.open(getIndex()));
            previewButton.setOnAction(e -> actions.preview(getIndex()));
            moreButton.setOnAction(e -> {
                javafx.geometry.Bounds b = moreButton.localToScreen(moreButton.getBoundsInLocal());
                if (b != null) {
                    showMenu(moreButton, getIndex(), b.getMinX(), b.getMaxY());
                }
            });
            top = new HBox(8, rank, name, demoTag, spacer, badge, quick);
            top.setAlignment(Pos.CENTER_LEFT);
            snippet.getStyleClass().add("snippet");
            coordinates.getStyleClass().add("hit-coord");
            telemetry.getStyleClass().add("hit-score");
            box = new VBox(3, top, snippet);
            setPrefWidth(0); // wrap to the list's width instead of widening it

            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !isEmpty()) {
                    actions.open(getIndex());
                    e.consume();
                }
            });
            setOnContextMenuRequested(e -> {
                if (isEmpty()) {
                    return;
                }
                showMenu(this, getIndex(), e.getScreenX(), e.getScreenY());
                e.consume();
            });
        }

        @Override
        protected void updateItem(Integer row, boolean isEmpty) {
            super.updateItem(row, isEmpty);
            render();
        }

        void render() {
            Integer row = getItem();
            ViewModeCoordinator v = view;
            if (isEmpty() || row == null || v == null) {
                setGraphic(null);
                return;
            }
            try {
                if (v.mode() == ViewMode.DETAILED) {
                    renderDetailed(v, v.detailed(row));
                } else {
                    renderSimple(v, v.simple(row));
                }
                setGraphic(box);
            } catch (IndexOutOfBoundsException raced) {
                // The table shrank on another thread; the pending results update will re-render this cell.
                setGraphic(null);
            }
        }

        private void renderSimple(ViewModeCoordinator v, SimpleView s) {
            rank.setText((s.row() + 1) + ".");
            name.setText(s.documentName());
            demoTag.setVisible(isSystemRow(s.row()));
            setBadge(badgesVisible ? "[" + s.relevance().label() + " " + s.relevancePercent() + "%]" : null,
                    "rel-" + s.relevance().name().toLowerCase(Locale.ROOT));
            fillSnippet(v, s.row(), s.snippet());
            box.getChildren().setAll(top, snippet);
        }

        private void renderDetailed(ViewModeCoordinator v, DetailedView d) {
            rank.setText((d.row() + 1) + ".");
            name.setText(d.documentName());
            demoTag.setVisible(actions.isSystemDocument(d.docId()));
            setBadge(String.format(Locale.ROOT, "bm25 %.3f · %d%%", d.bm25(), d.relevancePercent()), "rel-raw");
            fillSnippet(v, d.row(), d.snippet());
            coordinates.setText(d.coordinate() + "   doc " + shortId(d.docId()) + "   tf=" + tf(d.termFrequencies())
                    + "   matches=" + d.matchCount());
            telemetry.setText(String.format(Locale.ROOT, "parse %s · query %.2f ms · %d chunks",
                    d.parseMillis() < 0 ? "n/a" : d.parseMillis() + " ms", d.queryMillis(), d.matchedChunks()));
            box.getChildren().setAll(top, snippet, coordinates, telemetry);
        }

        private void setBadge(String text, String style) {
            boolean mounted = top.getChildren().contains(badge);
            if (text == null) {
                if (mounted) {
                    top.getChildren().remove(badge);
                }
                return;
            }
            if (!mounted) {
                top.getChildren().add(top.getChildren().indexOf(quick), badge);
            }
            badge.setText(text);
            if (!style.equals(badgeStyle)) {
                if (badgeStyle != null) {
                    badge.getStyleClass().remove(badgeStyle);
                }
                badge.getStyleClass().add(style);
                badgeStyle = style;
            }
        }

        /** Splits the snippet at match offsets so matches render with the {@code .match} style. */
        private void fillSnippet(ViewModeCoordinator v, int row, String text) {
            List<Node> parts = new ArrayList<>(8);
            SearchHit hit = v.hit(row).orElse(null);
            int cursor = 0;
            if (hit != null) {
                for (Location m : hit.matches()) {
                    long rel = m.offset() - hit.snippetOffset();
                    if (rel < cursor || rel + m.length() > text.length()) {
                        continue; // outside the snippet window, or overlapping the previous match
                    }
                    int from = (int) rel;
                    if (from > cursor) {
                        parts.add(text(text.substring(cursor, from), "snippet-text"));
                    }
                    parts.add(text(text.substring(from, from + m.length()), "match"));
                    cursor = from + m.length();
                }
            }
            String lead = hit != null && hit.clippedStart() ? "…" : "";
            String tail = hit != null && hit.clippedEnd() ? "…" : "";
            if (cursor < text.length()) {
                parts.add(text(text.substring(cursor), "snippet-text"));
            }
            if (!lead.isEmpty()) {
                parts.addFirst(text(lead, "snippet-text"));
            }
            if (!tail.isEmpty()) {
                parts.add(text(tail, "snippet-text"));
            }
            snippet.getChildren().setAll(parts);
        }
    }

    private static Button rowButton(String text, String tooltip) {
        Button b = new Button(text);
        b.getStyleClass().add("row-action-button");
        b.setFocusTraversable(false);
        b.setTooltip(new Tooltip(tooltip));
        return b;
    }

    private static Text text(String s, String style) {
        Text t = new Text(s);
        t.getStyleClass().add(style);
        return t;
    }

    private static String tf(Map<String, Integer> frequencies) {
        StringBuilder sb = new StringBuilder("{");
        frequencies.forEach((term, n) -> {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(term).append('=').append(n);
        });
        return sb.append('}').toString();
    }

    private static String shortId(String docId) {
        return docId == null || docId.length() < 10 ? String.valueOf(docId) : docId.substring(0, 10);
    }
}
