package org.example.gui.components;

import org.example.core.AnswerModel.AnswerStats;
import org.example.core.Workbench;
import org.example.model.Location;
import org.example.model.SearchHit;
import org.example.model.SearchResult;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Centre/right panel: retrieval evidence and the streaming answer.
 *
 * <p>Three stacked regions: the answer (tokens arrive one delta at a time and citations become clickable once the
 * stream finishes), the ranked BM25 hits with their query terms highlighted inside the snippet, and a context pane
 * that shows the full chunk behind a citation or a hit. Coordinates are shown the way the extractor recorded them:
 * {@code p.4 #2} for paged documents and {@code Row 8412} for spreadsheet blocks.</p>
 *
 * <p>Pure view: it renders what it is given and reports clicks through {@link Actions}. All methods must be called
 * on the JavaFX application thread.</p>
 */
public final class EvidenceStreamView extends BorderPane {

    /** Gestures the evidence stream reports to the controller. */
    public interface Actions {

        /** Show the full chunk behind a hit or a citation, highlighting {@code length} characters at {@code offset}. */
        void reveal(String docId, int chunkIndex, long offset, int length);
    }

    /** Refusal sentence of the engine; shown verbatim, never paraphrased. */
    public static final String NOT_IN_DOCUMENT = "Belgede bu bilgi yer almamaktadır.";

    private static final Locale TR = Locale.of("tr", "TR");
    private static final Pattern CITATION = Pattern.compile("\\[S(\\d{1,2})]");
    private static final Pattern ROW_MARKER = Pattern.compile("\\|\\s*Row\\s+(\\d+)");
    private static final Pattern SHEET_MARKER = Pattern.compile("=== (?:Sayfa|Slayt):? ?([^=]{0,40})===");
    private static final int MAX_CONTEXT_CHARS = 20_000;

    private final Actions actions;

    private final Label headline = new Label("Kanıt akışı");
    private final Label headMeta = new Label();

    private final TextFlow answerFlow = new TextFlow();
    private final ScrollPane answerScroll = new ScrollPane(answerFlow);
    private final VBox sourceBar = new VBox(4);
    private final Label answerTitle = new Label("Yanıt");

    private final ObservableList<SearchHit> hits = FXCollections.observableArrayList();
    private final ListView<SearchHit> hitList = new ListView<>(hits);

    private final Label contextTitle = new Label();
    private final TextFlow contextFlow = new TextFlow();
    private final ScrollPane contextScroll = new ScrollPane(contextFlow);
    private final VBox contextPane = new VBox(6, contextTitle, contextScroll);

    private final Text streaming = new Text();
    private List<Workbench.Source> sources = List.of();

    public EvidenceStreamView(Actions actions) {
        this.actions = actions;
        getStyleClass().add("evidence-stream");

        headline.getStyleClass().add("panel-title");
        headMeta.getStyleClass().add("panel-subtitle");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(10, headline, spacer, headMeta);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(14, 16, 10, 16));
        header.getStyleClass().add("panel-header");
        setTop(header);

        answerTitle.getStyleClass().add("section-title");
        answerFlow.getStyleClass().add("answer-flow");
        answerScroll.getStyleClass().add("answer-scroll");
        answerScroll.setFitToWidth(true);
        sourceBar.getStyleClass().add("source-bar");
        VBox answerCard = new VBox(8, answerTitle, answerScroll, sourceBar);
        answerCard.setPadding(new Insets(12, 16, 12, 16));
        answerCard.getStyleClass().add("answer-card");
        VBox.setVgrow(answerScroll, Priority.ALWAYS);

        Label hitsTitle = new Label("BM25 sonuçları");
        hitsTitle.getStyleClass().add("section-title");
        Label placeholder = new Label("Arama yapın: üstteki çubuğa yazın. Soru sormak için satırı ? ile başlatın.");
        placeholder.getStyleClass().add("placeholder");
        placeholder.setWrapText(true);
        hitList.setPlaceholder(placeholder);
        hitList.getStyleClass().add("hit-list");
        hitList.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        hitList.setCellFactory(view -> new HitCell());
        VBox hitsBox = new VBox(8, hitsTitle, hitList);
        hitsBox.setPadding(new Insets(12, 16, 12, 16));
        hitsBox.getStyleClass().add("hits-card");
        VBox.setVgrow(hitList, Priority.ALWAYS);

        SplitPane split = new SplitPane();
        split.setOrientation(Orientation.VERTICAL);
        split.getItems().addAll(answerCard, hitsBox);
        split.setDividerPositions(0.42);
        split.getStyleClass().add("evidence-split");
        setCenter(split);

        contextTitle.getStyleClass().add("section-title");
        contextFlow.getStyleClass().add("context-flow");
        contextScroll.setFitToWidth(true);
        contextScroll.setPrefHeight(180);
        contextPane.setPadding(new Insets(10, 16, 14, 16));
        contextPane.getStyleClass().add("context-pane");
        hideContext();
        setBottom(contextPane);

        streaming.getStyleClass().add("answer-text");
        clearAnswer("Bir soru sorun: komut çubuğunda satırı ? ile başlatın.");
    }

    // ------------------------------------------------------------------ search

    /** Renders a completed keyword search. */
    public void showSearch(SearchResult result) {
        headline.setText("Kanıt akışı · BM25");
        headMeta.setText(String.format(TR, "%d sonuç · %,d eşleşen parça · %.2f ms · terimler: %s",
                result.hits().size(), result.matchedChunks(), result.elapsedMillis(),
                result.terms().isEmpty() ? "-" : String.join(", ", result.terms())));
        hits.setAll(result.hits());
        if (result.isEmpty()) {
            hitList.setPlaceholder(styled(new Label("Eşleşme yok: '" + result.query() + "'"), "placeholder"));
        }
    }

    /** Clears the hit list while a new query is running. */
    public void searching(String query) {
        headline.setText("Kanıt akışı · BM25");
        headMeta.setText("aranıyor: " + query);
    }

    // ------------------------------------------------------------------ answering

    /** Starts a new answer: the question is echoed and the stream target is reset. */
    public void beginAnswer(String question) {
        headline.setText("Kanıt akışı · Yanıt");
        headMeta.setText("kaynaklar toplanıyor…");
        sources = List.of();
        sourceBar.getChildren().clear();
        answerTitle.setText("Yanıt · " + oneLine(question, 120));
        streaming.setText("");
        answerFlow.getChildren().setAll(streaming);
        answerScroll.setVvalue(0);
    }

    /** Appends a streamed delta. Called on the FX thread by the controller's coalescing flusher. */
    public void appendAnswer(String delta) {
        streaming.setText(streaming.getText() + delta);
        answerScroll.setVvalue(1.0);
    }

    /** Replaces the streamed text with the final, citation-aware rendering. */
    public void completeAnswer(String answer, Workbench.AskOutcome outcome) {
        sources = outcome.sources();
        answerFlow.getChildren().setAll(renderAnswer(answer));
        renderSourceBar(outcome.sources());
        AnswerStats stats = outcome.stats();
        if (stats == null) {
            headMeta.setText(String.format(TR, "%d kaynak · getirme %.2f ms", sources.size(), outcome.retrievalMillis()));
        } else {
            headMeta.setText(String.format(TR,
                    "%d kaynak · getirme %.2f ms · ilk jeton %d ms · toplam %d ms · %d+%d jeton · %s",
                    sources.size(), outcome.retrievalMillis(), stats.firstTokenMillis(), stats.totalMillis(),
                    stats.promptTokens(), stats.outputTokens(), stats.model()));
        }
    }

    /**
     * Retrieval produced nothing usable, so the model was never called: the engine's own refusal sentence is
     * shown, with the reason as a sub-line.
     */
    public void showRefusal(Workbench.AskOutcome outcome) {
        sources = List.of();
        sourceBar.getChildren().clear();
        Text sentence = new Text(NOT_IN_DOCUMENT);
        sentence.getStyleClass().add("refusal-text");
        answerFlow.getChildren().setAll(sentence);
        String reason = switch (outcome.outcome()) {
            case NO_MATCH -> "anahtar kelime eşleşmesi yok";
            case BELOW_THRESHOLD -> String.format(TR, "en iyi parça eşiğin altında (skor %.2f)", outcome.topScore());
            case ANSWERED -> "";
        };
        headMeta.setText("model çağrılmadı · " + reason + String.format(TR, " · getirme %.2f ms",
                outcome.retrievalMillis()));
        hits.clear();
    }

    /** Shows an engine-level message (missing key, quota, network) in the answer area. */
    public void showNotice(String message) {
        sourceBar.getChildren().clear();
        Text text = new Text(message);
        text.getStyleClass().add("notice-text");
        answerFlow.getChildren().setAll(text);
    }

    /** Resets the answer area to a placeholder. */
    public void clearAnswer(String placeholder) {
        sources = List.of();
        sourceBar.getChildren().clear();
        answerTitle.setText("Yanıt");
        Text text = new Text(placeholder);
        text.getStyleClass().add("placeholder-text");
        answerFlow.getChildren().setAll(text);
    }

    // ------------------------------------------------------------------ context pane

    /** Shows the full chunk behind a citation, with the matched span highlighted. */
    public void showContext(String title, String body, int highlightStart, int highlightLength) {
        contextTitle.setText(title);
        String text = body.length() > MAX_CONTEXT_CHARS ? body.substring(0, MAX_CONTEXT_CHARS) + " […]" : body;
        List<Node> parts = new ArrayList<>(3);
        int start = Math.max(0, Math.min(highlightStart, text.length()));
        int end = Math.max(start, Math.min(start + Math.max(0, highlightLength), text.length()));
        if (start > 0) {
            parts.add(plain(text.substring(0, start)));
        }
        if (end > start) {
            Text match = new Text(text.substring(start, end));
            match.getStyleClass().add("match");
            parts.add(match);
        }
        if (end < text.length()) {
            parts.add(plain(text.substring(end)));
        }
        contextFlow.getChildren().setAll(parts);
        contextPane.setVisible(true);
        contextPane.setManaged(true);
        contextScroll.setVvalue(text.isEmpty() ? 0 : Math.min(1.0, start / (double) Math.max(1, text.length())));
    }

    public void hideContext() {
        contextPane.setVisible(false);
        contextPane.setManaged(false);
        contextFlow.getChildren().clear();
    }

    // ------------------------------------------------------------------ rendering helpers

    /** Splits the answer into plain text and clickable {@code [S1]} citation chips. */
    private List<Node> renderAnswer(String answer) {
        List<Node> parts = new ArrayList<>();
        Matcher matcher = CITATION.matcher(answer);
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() > cursor) {
                parts.add(answerText(answer.substring(cursor, matcher.start())));
            }
            String tag = "S" + Integer.parseInt(matcher.group(1));
            Text chip = new Text(matcher.group());
            chip.getStyleClass().add("citation");
            chip.setOnMouseClicked(event -> {
                reveal(tag);
                event.consume();
            });
            parts.add(chip);
            cursor = matcher.end();
        }
        if (cursor < answer.length()) {
            parts.add(answerText(answer.substring(cursor)));
        }
        if (parts.isEmpty()) {
            parts.add(answerText(answer));
        }
        return parts;
    }

    private void renderSourceBar(List<Workbench.Source> list) {
        sourceBar.getChildren().clear();
        if (list.isEmpty()) {
            return;
        }
        Label title = new Label("Kaynaklar");
        title.getStyleClass().add("section-title");
        sourceBar.getChildren().add(title);
        for (Workbench.Source source : list) {
            Label tag = new Label("[" + source.tag() + "]");
            tag.getStyleClass().add("source-tag");
            Label name = new Label(source.fileName());
            name.getStyleClass().add("source-name");
            Label coord = new Label(coordinate(source.page(), source.chunkIndex(), null)
                    + String.format(TR, " · skor %.2f", source.score()));
            coord.getStyleClass().add("source-coord");
            HBox row = new HBox(8, tag, name, coord);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("source-row");
            row.setOnMouseClicked(event -> {
                actions.reveal(source.docId(), source.chunkIndex(), source.offset(), 0);
                event.consume();
            });
            sourceBar.getChildren().add(row);
        }
    }

    private void reveal(String tag) {
        sources.stream()
                .filter(s -> s.tag().equals(tag))
                .findFirst()
                .ifPresent(s -> actions.reveal(s.docId(), s.chunkIndex(), s.offset(), 0));
    }

    /** Builds the snippet with every query-term occurrence highlighted, exactly where the index found it. */
    private static TextFlow snippetFlow(SearchHit hit) {
        TextFlow flow = new TextFlow();
        flow.getStyleClass().add("snippet");
        String snippet = hit.snippet();
        List<Node> parts = new ArrayList<>();
        if (hit.clippedStart()) {
            parts.add(plain("… "));
        }
        int cursor = 0;
        for (Location match : hit.matches()) {
            int from = (int) (match.offset() - hit.snippetOffset());
            int to = from + match.length();
            if (from < cursor || from < 0 || to > snippet.length()) {
                continue;
            }
            if (from > cursor) {
                parts.add(plain(snippet.substring(cursor, from)));
            }
            Text highlighted = new Text(snippet.substring(from, to));
            highlighted.getStyleClass().add("match");
            parts.add(highlighted);
            cursor = to;
        }
        if (cursor < snippet.length()) {
            parts.add(plain(snippet.substring(cursor)));
        }
        if (hit.clippedEnd()) {
            parts.add(plain(" …"));
        }
        flow.getChildren().setAll(parts);
        return flow;
    }

    /** {@code p.4 #2} for paged formats, {@code Row 8412 #17} for spreadsheet blocks, {@code #17} otherwise. */
    private static String coordinate(int page, int chunkIndex, String snippet) {
        if (page > 0) {
            return "p." + page + " #" + chunkIndex;
        }
        if (snippet != null) {
            Matcher row = ROW_MARKER.matcher(snippet);
            if (row.find()) {
                return "Row " + row.group(1) + " #" + chunkIndex;
            }
            Matcher sheet = SHEET_MARKER.matcher(snippet);
            if (sheet.find()) {
                return oneLine(sheet.group(1), 24) + " #" + chunkIndex;
            }
        }
        return "#" + chunkIndex;
    }

    private static Text plain(String text) {
        Text node = new Text(text);
        node.getStyleClass().add("snippet-text");
        return node;
    }

    private static Text answerText(String text) {
        Text node = new Text(text);
        node.getStyleClass().add("answer-text");
        return node;
    }

    private static Label styled(Label label, String styleClass) {
        label.getStyleClass().add(styleClass);
        label.setWrapText(true);
        return label;
    }

    private static String oneLine(String text, int max) {
        String flat = text.replace('\n', ' ').strip();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    // ------------------------------------------------------------------ hit cell

    private final class HitCell extends ListCell<SearchHit> {

        private final Label rank = new Label();
        private final Label file = new Label();
        private final Label coord = new Label();
        private final Label score = new Label();
        private final HBox head = new HBox(8, rank, file, coord, score);
        private final VBox body = new VBox(4, head);

        HitCell() {
            rank.getStyleClass().add("hit-rank");
            file.getStyleClass().add("hit-file");
            coord.getStyleClass().add("hit-coord");
            score.getStyleClass().add("hit-score");
            head.setAlignment(Pos.CENTER_LEFT);
            body.getStyleClass().add("hit-cell");
            getStyleClass().add("hit-row");
        }

        @Override
        protected void updateItem(SearchHit hit, boolean empty) {
            super.updateItem(hit, empty);
            if (empty || hit == null) {
                setText(null);
                setGraphic(null);
                setOnMouseClicked(null);
                return;
            }
            rank.setText("[S" + (hits.indexOf(hit) + 1) + "]");
            file.setText(hit.fileName());
            coord.setText(coordinate(hit.page(), hit.chunkIndex(), hit.snippet()));
            score.setText(String.format(TR, "%.2f", hit.score()));
            body.getChildren().setAll(head, snippetFlow(hit));
            setText(null);
            setGraphic(body);
            int span = hit.matches().isEmpty() ? 0 : hit.matches().getFirst().length();
            setOnMouseClicked(event -> {
                actions.reveal(hit.docId(), hit.chunkIndex(), hit.firstMatchOffset(), span);
                event.consume();
            });
        }
    }
}
