package org.example.gui;

import org.example.core.AnswerModel.AnswerException;
import org.example.core.DocumentIngestor.IngestResult;
import org.example.core.HeapGuard;
import org.example.core.IngestionException;
import org.example.core.QuotaGate;
import org.example.core.SearchIndex;
import org.example.core.Workbench;
import org.example.gui.components.ActiveDeskView;
import org.example.gui.components.EvidenceStreamView;
import org.example.llm.GeminiStreamEngine;
import org.example.llm.ModelProfile;
import org.example.model.DocumentRecord;
import org.example.model.SearchResult;
import org.example.model.TextChunk;
import org.example.util.Hashing;
import org.example.watcher.FileWatcherService;
import org.example.workspace.WorkspaceManager;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Chassis controller: builds the three-region layout and drives the headless engine from it.
 *
 * <p>Threading contract — the whole point of this class. Every call into the engine (ingestion, BM25 search,
 * SHA-256 hashing, native open, watcher setup, the streamed answer) runs on a {@link Thread#ofVirtual() virtual
 * thread}; every mutation of a JavaFX node happens inside {@link Platform#runLater(Runnable)}. Streamed answer
 * deltas are coalesced into at most one UI task at a time, so a fast token stream cannot flood the FX event queue.
 * The UI thread therefore never waits on I/O, on the network or on a lock held by the engine.</p>
 *
 * <p>The command bar is the only input surface: plain text is a debounced BM25 search, a line starting with
 * {@code ?} is a grounded question. Local search never consults the quota, so it keeps working after the daily AI
 * budget is gone.</p>
 */
public final class WorkbenchController implements ActiveDeskView.Actions, EvidenceStreamView.Actions {

    private static final Locale TR = Locale.of("tr", "TR");
    private static final Duration SEARCH_DEBOUNCE = Duration.millis(220);
    private static final long TELEMETRY_INTERVAL_MILLIS = 1_500;
    private static final int SEARCH_LIMIT = 40;
    private static final int MAX_WALK_DEPTH = 8;

    private final Workbench workbench;
    private final WorkspaceManager workspace;
    private final GeminiStreamEngine engine;

    private final ActiveDeskView desk = new ActiveDeskView(this);
    private final EvidenceStreamView evidence = new EvidenceStreamView(this);
    private final BorderPane root = new BorderPane();
    private final TextField commandBar = new TextField();
    private final Label modeChip = new Label("BM25");
    private final Label hint = new Label("Enter: ara · ? ile başlayın: belgeye soru sorun");
    private final ComboBox<String> modelBox = new ComboBox<>();
    private final Label quotaLabel = new Label();
    private final Label indexLabel = new Label();
    private final Label memoryLabel = new Label();
    private final Label messageLabel = new Label();

    private final PauseTransition searchDebounce = new PauseTransition(SEARCH_DEBOUNCE);
    private final AtomicLong searchGeneration = new AtomicLong();
    private final AtomicBoolean answering = new AtomicBoolean();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private final StringBuilder streamBuffer = new StringBuilder();
    private final StringBuilder answerBuffer = new StringBuilder();
    private final AtomicBoolean flushScheduled = new AtomicBoolean();

    private volatile ModelProfile activeProfile;
    private volatile FileWatcherService watcher;
    private Thread telemetry;
    private Stage stage;

    public WorkbenchController(Workbench workbench, WorkspaceManager workspace, GeminiStreamEngine engine,
                               ModelProfile profile) {
        this.workbench = Objects.requireNonNull(workbench, "workbench must not be null");
        this.workspace = Objects.requireNonNull(workspace, "workspace must not be null");
        this.engine = engine;
        this.activeProfile = profile == null ? ModelProfile.DEFAULT : profile;
        buildLayout();
    }

    /** The scene root; add it to a {@link javafx.scene.Scene}. */
    public Parent view() {
        return root;
    }

    /** Called once the window is on screen: focuses the command bar and starts the telemetry ticker. */
    public void onShown(Stage owner) {
        this.stage = owner;
        commandBar.requestFocus();
        refreshDesk();
        updateTelemetry();
        telemetry = Thread.ofVirtual().name("dwb-gui-telemetry").start(this::telemetryLoop);
    }

    // ------------------------------------------------------------------ layout

    private void buildLayout() {
        root.getStyleClass().add("workbench-root");
        root.setTop(commandBar());
        SplitPane split = new SplitPane();
        split.setOrientation(Orientation.HORIZONTAL);
        split.getItems().addAll(desk, evidence);
        split.setDividerPositions(0.28);
        split.getStyleClass().add("main-split");
        SplitPane.setResizableWithParent(desk, Boolean.FALSE);
        root.setCenter(split);
        root.setBottom(statusBar());
    }

    private Region commandBar() {
        modeChip.getStyleClass().addAll("mode-chip", "mode-search");
        modeChip.setTooltip(new Tooltip("Yerel BM25 araması (AI kotası harcamaz)"));

        commandBar.getStyleClass().add("command-bar");
        commandBar.setPromptText("Ara…  ·  soru sormak için satırı ? ile başlatın");
        commandBar.textProperty().addListener((observable, old, value) -> onCommandText(value));
        commandBar.setOnAction(event -> submit());

        hint.getStyleClass().add("command-hint");
        HBox.setHgrow(commandBar, Priority.ALWAYS);

        HBox bar = new HBox(10, modeChip, commandBar, hint);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(14, 16, 12, 16));
        bar.getStyleClass().add("command-row");
        VBox top = new VBox(bar);
        top.getStyleClass().add("command-container");
        return top;
    }

    private Region statusBar() {
        modelBox.getItems().setAll(Arrays.stream(ModelProfile.values()).map(ModelProfile::id).toList());
        modelBox.setValue(activeProfile.id());
        modelBox.getStyleClass().add("model-box");
        modelBox.setTooltip(new Tooltip("Etkin Gemini modeli"));
        modelBox.setDisable(engine == null);
        modelBox.setOnAction(event -> selectModel(modelBox.getValue()));

        quotaLabel.getStyleClass().add("status-item");
        indexLabel.getStyleClass().add("status-item");
        memoryLabel.getStyleClass().add("status-item");
        messageLabel.getStyleClass().add("status-message");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(14, modelBox, quotaLabel, indexLabel, memoryLabel, spacer, messageLabel);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 16, 8, 16));
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    // ------------------------------------------------------------------ command bar

    private void onCommandText(String value) {
        boolean ask = value != null && value.stripLeading().startsWith("?");
        modeChip.setText(ask ? "SOR" : "BM25");
        modeChip.getStyleClass().removeAll("mode-search", "mode-ask");
        modeChip.getStyleClass().add(ask ? "mode-ask" : "mode-search");
        hint.setText(ask
                ? "Enter: belgelere dayalı yanıt (AI kotası harcar)"
                : "Enter: ara · ? ile başlayın: belgeye soru sorun");
        searchDebounce.stop();
        if (ask) {
            return;
        }
        String query = value == null ? "" : value.strip();
        if (query.length() < 2) {
            searchGeneration.incrementAndGet();
            return;
        }
        searchDebounce.setOnFinished(event -> runSearch(query));
        searchDebounce.playFromStart();
    }

    private void submit() {
        String text = commandBar.getText() == null ? "" : commandBar.getText().strip();
        if (text.isEmpty()) {
            return;
        }
        searchDebounce.stop();
        if (text.startsWith("?")) {
            String question = text.substring(1).strip();
            if (!question.isEmpty()) {
                runAsk(question);
            }
            return;
        }
        runSearch(text);
    }

    // ------------------------------------------------------------------ retrieval

    private void runSearch(String query) {
        long generation = searchGeneration.incrementAndGet();
        evidence.searching(query);
        Thread.ofVirtual().name("dwb-gui-search").start(() -> {
            SearchResult result = workbench.find(query, SEARCH_LIMIT);
            if (generation != searchGeneration.get()) {
                return;
            }
            ui(() -> {
                evidence.showSearch(result);
                message(String.format(TR, "%d sonuç · %.2f ms", result.hits().size(), result.elapsedMillis()));
            });
        });
    }

    private void runAsk(String question) {
        if (engine == null) {
            evidence.showNotice("GEMINI_API_KEY tanımlı değil: yanıt üretimi kapalı. Yerel arama çalışmaya devam eder.");
            return;
        }
        if (!answering.compareAndSet(false, true)) {
            message("Önceki yanıt hâlâ akıyor.");
            return;
        }
        evidence.beginAnswer(question);
        evidence.hideContext();
        message("yanıt alınıyor…");
        synchronized (streamBuffer) {
            streamBuffer.setLength(0);
            answerBuffer.setLength(0);
        }
        Thread.ofVirtual().name("dwb-gui-ask").start(() -> {
            try {
                Workbench.AskOutcome outcome = workbench.ask(question, this::onDelta);
                String answer = drainStream();
                ui(() -> {
                    if (outcome.answered()) {
                        evidence.completeAnswer(answer, outcome);
                        message("yanıt tamam");
                    } else {
                        evidence.showRefusal(outcome);
                        message("model çağrılmadı");
                    }
                    updateTelemetry();
                });
            } catch (AnswerException e) {
                drainStream();
                ui(() -> {
                    evidence.showNotice(describe(e));
                    message(e.kind().name().toLowerCase(Locale.ROOT));
                    updateTelemetry();
                });
            } finally {
                answering.set(false);
            }
        });
    }

    private static String describe(AnswerException e) {
        String prefix = switch (e.kind()) {
            case NOT_CONFIGURED -> "Model yapılandırılmadı";
            case QUOTA_EXCEEDED -> "AI kotası";
            case AUTHENTICATION -> "Kimlik doğrulama";
            case BAD_REQUEST -> "Geçersiz istek";
            case RATE_LIMITED -> "Hız sınırı";
            case UNAVAILABLE -> "Servis kullanılamıyor";
            case TIMEOUT -> "Zaman aşımı";
            case BLOCKED -> "İçerik engellendi";
            case NETWORK -> "Ağ hatası";
            case PROTOCOL -> "Protokol hatası";
            case ABORTED -> "İptal edildi";
        };
        return prefix + ": " + e.getMessage();
    }

    /**
     * Token sink, called on the streaming thread. Deltas are buffered and at most one flush task is queued at a
     * time, so a burst of small deltas becomes one UI update instead of hundreds.
     */
    private void onDelta(String delta) {
        synchronized (streamBuffer) {
            streamBuffer.append(delta);
            answerBuffer.append(delta);
        }
        if (flushScheduled.compareAndSet(false, true)) {
            Platform.runLater(this::flushStream);
        }
    }

    private void flushStream() {
        String pending;
        synchronized (streamBuffer) {
            pending = streamBuffer.toString();
            streamBuffer.setLength(0);
        }
        flushScheduled.set(false);
        if (!pending.isEmpty()) {
            evidence.appendAnswer(pending);
        }
    }

    /** Flushes whatever the sink still holds and returns the complete answer text of this turn. */
    private String drainStream() {
        String pending;
        String complete;
        synchronized (streamBuffer) {
            pending = streamBuffer.toString();
            streamBuffer.setLength(0);
            complete = answerBuffer.toString();
        }
        flushScheduled.set(false);
        if (!pending.isEmpty()) {
            ui(() -> evidence.appendAnswer(pending));
        }
        return complete;
    }

    // ------------------------------------------------------------------ ActiveDeskView.Actions

    @Override
    public void ingest(List<File> files) {
        List<Path> paths = files.stream().map(File::toPath).toList();
        Thread.ofVirtual().name("dwb-gui-ingest").start(() -> {
            int added = 0;
            int skipped = 0;
            List<String> failures = new ArrayList<>();
            for (Path path : expand(paths)) {
                try {
                    IngestResult result = workbench.ingest(path, null, DocumentRecord.LOCAL);
                    if (result instanceof IngestResult.Ingested(DocumentRecord document, long millis)) {
                        workspace.register(document);
                        watchQuietly(document);
                        added++;
                        String note = String.format(TR, "%s · %,d parça · %d ms", document.fileName(),
                                document.chunks().size(), millis);
                        ui(() -> message(note));
                    } else {
                        skipped++;
                    }
                } catch (IngestionException e) {
                    failures.add(path.getFileName() + " (" + e.reason() + ")");
                }
            }
            int indexed = added;
            int duplicates = skipped;
            ui(() -> {
                refreshDesk();
                updateTelemetry();
                StringBuilder sb = new StringBuilder();
                sb.append(indexed).append(" belge indekslendi");
                if (duplicates > 0) {
                    sb.append(" · ").append(duplicates).append(" yinelenen atlandı");
                }
                if (!failures.isEmpty()) {
                    sb.append(" · başarısız: ").append(String.join(", ", failures));
                }
                message(sb.toString());
            });
        });
    }

    /** Expands dropped directories into the supported files below them. */
    private List<Path> expand(List<Path> roots) {
        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            if (Files.isRegularFile(root)) {
                files.add(root);
                continue;
            }
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root, MAX_WALK_DEPTH)) {
                walk.filter(Files::isRegularFile).filter(workbench::supports).forEach(files::add);
            } catch (IOException | UncheckedIOException e) {
                ui(() -> message("okunamadı: " + root + " (" + e.getMessage() + ")"));
            }
        }
        return files;
    }

    @Override
    public void chooseFiles() {
        if (stage == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Masaya belge ekle");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Desteklenen belgeler", java.util.stream.Stream.concat(
                        java.util.stream.Stream.of("*.pdf", "*.docx", "*.xlsx", "*.xlsm", "*.pptx", "*.pptm", "*.ppt",
                                "*.csv", "*.tsv", "*.txt", "*.md", "*.log"),
                        org.example.model.ContentKind.binaryPatterns().stream()).toList()),
                new FileChooser.ExtensionFilter("Tüm dosyalar", "*.*"));
        List<File> chosen = chooser.showOpenMultipleDialog(stage);
        if (chosen != null && !chosen.isEmpty()) {
            ingest(chosen);
        }
    }

    @Override
    public void open(DocumentRecord document) {
        Thread.ofVirtual().name("dwb-gui-open").start(() -> {
            try {
                WorkspaceManager.Entry entry = workspace.resolve(document.sha256())
                        .orElseGet(() -> workspace.register(document));
                WorkspaceManager.OpenResult result = workspace.open(entry);
                ui(() -> message("açıldı: " + entry.fileName() + " (" + result.method() + ")"));
            } catch (WorkspaceManager.WorkspaceException e) {
                ui(() -> message("açılamadı: " + e.getMessage()));
            }
        });
    }

    @Override
    public void untrack(DocumentRecord document) {
        Thread.ofVirtual().name("dwb-gui-untrack").start(() -> {
            workbench.remove(document.sha256());
            workspace.remove(document.sha256());
            FileWatcherService service = watcher;
            if (service != null) {
                service.unwatch(document.source());
            }
            ui(() -> {
                refreshDesk();
                updateTelemetry();
                evidence.hideContext();
                message("indeksten çıkarıldı: " + document.fileName() + " (dosya diskte kaldı)");
            });
        });
    }

    @Override
    public void watchToggled(boolean enabled) {
        Thread.ofVirtual().name("dwb-gui-watch").start(() -> {
            if (enabled) {
                startWatching();
            } else {
                stopWatching();
            }
        });
    }

    // ------------------------------------------------------------------ EvidenceStreamView.Actions

    @Override
    public void reveal(String docId, int chunkIndex, long offset, int length) {
        Thread.ofVirtual().name("dwb-gui-reveal").start(() -> {
            SearchIndex index = workbench.index();
            Optional<String> text = index.chunkText(docId, chunkIndex);
            if (text.isEmpty()) {
                ui(() -> message("parça artık indekste değil"));
                return;
            }
            Optional<DocumentRecord> document = index.document(docId);
            String name = document.map(DocumentRecord::fileName).orElse(docId.substring(0, 12));
            long chunkStart = document.map(d -> d.chunks().stream()
                            .filter(c -> c.index() == chunkIndex)
                            .findFirst()
                            .map(TextChunk::startOffset)
                            .orElse(0L))
                    .orElse(0L);
            int page = document.map(d -> d.chunks().stream()
                            .filter(c -> c.index() == chunkIndex)
                            .findFirst()
                            .map(TextChunk::page)
                            .orElse(-1))
                    .orElse(-1);
            int start = (int) Math.max(0, offset - chunkStart);
            String title = name + (page > 0 ? " · s." + page : "") + " · parça " + chunkIndex;
            String body = text.get();
            ui(() -> {
                evidence.showContext(title, body, start, length);
                desk.reveal(docId);
            });
        });
    }

    // ------------------------------------------------------------------ watcher

    private void startWatching() {
        if (watcher != null && watcher.running()) {
            return;
        }
        FileWatcherService service = new FileWatcherService(new FileWatcherService.Listener() {
            @Override
            public void modified(Path file) {
                reindex(file);
            }

            @Override
            public void deleted(Path file) {
                workbench.documents().stream()
                        .filter(d -> d.source().equals(file))
                        .findFirst()
                        .ifPresent(document -> {
                            workbench.remove(document.sha256());
                            workspace.remove(document.sha256());
                            ui(() -> {
                                refreshDesk();
                                message("diskten silindi, indeksten çıkarıldı: " + document.fileName());
                            });
                        });
            }

            @Override
            public void failed(Path path, Exception error) {
                ui(() -> message("izleme hatası: " + path.getFileName() + " (" + error.getMessage() + ")"));
            }
        });
        try {
            service.start();
        } catch (IOException e) {
            ui(() -> {
                desk.setWatchEnabled(false);
                message("izleme başlatılamadı: " + e.getMessage());
            });
            return;
        }
        watcher = service;
        int registered = 0;
        for (DocumentRecord document : workbench.documents()) {
            registered += watchQuietly(document) ? 1 : 0;
        }
        int count = registered;
        ui(() -> {
            desk.setWatchEnabled(true);
            message(String.format(TR, "canlı izleme açık · %d dosya · %d ms gecikme", count,
                    service.debounce().toMillis()));
        });
    }

    private void stopWatching() {
        FileWatcherService service = watcher;
        watcher = null;
        if (service != null) {
            service.close();
        }
        ui(() -> {
            desk.setWatchEnabled(false);
            message("canlı izleme kapalı");
        });
    }

    private boolean watchQuietly(DocumentRecord document) {
        FileWatcherService service = watcher;
        if (service == null || !service.running() || !document.local()) {
            return false;
        }
        try {
            return service.watch(document.source());
        } catch (IOException | RuntimeException e) {
            ui(() -> message("izlenemiyor: " + document.source().getFileName() + " (" + e.getMessage() + ")"));
            return false;
        }
    }

    /** Re-ingests a file the watcher reported as changed; identical bytes are ignored. */
    private void reindex(Path file) {
        Optional<DocumentRecord> current = workbench.documents().stream()
                .filter(d -> d.source().equals(file))
                .findFirst();
        try {
            String hash = Hashing.sha256Hex(file);
            if (current.isPresent() && current.get().sha256().equals(hash)) {
                return;
            }
            current.ifPresent(document -> {
                workbench.remove(document.sha256());
                workspace.remove(document.sha256());
            });
            IngestResult result = workbench.ingest(file, null, DocumentRecord.LOCAL);
            if (result instanceof IngestResult.Ingested(DocumentRecord document, long millis)) {
                workspace.register(document);
                watchQuietly(document);
                ui(() -> {
                    refreshDesk();
                    updateTelemetry();
                    message(String.format(TR, "yeniden indekslendi: %s · %,d parça · %d ms", document.fileName(),
                            document.chunks().size(), millis));
                });
            }
        } catch (IngestionException | IOException e) {
            ui(() -> message("yeniden indekslenemedi: " + file.getFileName() + " (" + e.getMessage() + ")"));
        }
    }

    // ------------------------------------------------------------------ telemetry

    private void telemetryLoop() {
        while (!shuttingDown.get()) {
            try {
                Thread.sleep(TELEMETRY_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            ui(this::updateTelemetry);
        }
    }

    /** Refreshes the bottom bar: model, remaining AI quota, index size and heap footprint. */
    private void updateTelemetry() {
        QuotaGate.Snapshot quota = workbench.quota().snapshot();
        quotaLabel.setText(quota.limit() == Integer.MAX_VALUE
                ? "AI kotası: sınırsız"
                : String.format(TR, "AI kotası: %d/%d", quota.used(), quota.limit()));
        quotaLabel.setTooltip(new Tooltip(quota.open()
                ? "Kalan " + quota.remaining() + " çağrı · " + quota.schedule()
                : "Kapalı: " + quota.schedule()));

        SearchIndex.Stats stats = workbench.index().stats();
        indexLabel.setText(String.format(TR, "İndeks: %,d belge · %,d parça · %,d terim",
                stats.documents(), stats.chunks(), stats.terms()));

        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        memoryLabel.setText(String.format(TR, "RAM: %,d MB / %,d MB (%%%.0f)", used >> 20,
                runtime.maxMemory() >> 20, HeapGuard.usageRatio() * 100));
    }

    private void refreshDesk() {
        desk.setDocuments(workbench.documents());
    }

    private void selectModel(String id) {
        if (engine == null || id == null) {
            return;
        }
        Optional<ModelProfile> profile = ModelProfile.parse(id);
        if (profile.isEmpty()) {
            return;
        }
        activeProfile = profile.get();
        Thread.ofVirtual().name("dwb-gui-model").start(() -> {
            engine.select(activeProfile);
            ui(() -> message("model: " + activeProfile.id()));
        });
    }

    private void message(String text) {
        messageLabel.setText(text);
    }

    /** Runs {@code task} on the JavaFX application thread, immediately when already on it. */
    private static void ui(Runnable task) {
        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            Platform.runLater(task);
        }
    }

    /** Stops the telemetry ticker and the watcher; safe to call twice. */
    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        searchDebounce.stop();
        if (telemetry != null) {
            telemetry.interrupt();
        }
        FileWatcherService service = watcher;
        watcher = null;
        if (service != null) {
            service.close();
        }
    }
}
