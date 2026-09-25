package org.example.gui;

import org.example.core.Workbench;
import org.example.index.InvertedIndex;
import org.example.ingest.DocumentParser;
import org.example.llm.GeminiStreamEngine;
import org.example.llm.ModelProfile;
import org.example.quota.QuotaManager;
import org.example.workspace.WorkspaceManager;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.net.URL;
import java.util.List;
import java.util.Optional;

/**
 * Native JavaFX shell around the headless engine: no WebView, no embedded HTTP server, no HTML — standard JavaFX
 * controls over the very same {@link Workbench} the console runner drives, inside one JVM and one process.
 *
 * <pre>
 * java --module-path $PATH_TO_FX/lib --add-modules javafx.controls \
 *      -cp app.jar:pdfbox-app-3.x.jar org.example.gui.WorkbenchApp [--model ID]
 * </pre>
 *
 * <p>Env: {@code GEMINI_API_KEY} enables answering (without it the workbench stays a local search tool),
 * {@code DWB_MODEL} preselects a model, {@code DWB_AI_QUOTA} / {@code DWB_AI_RATE} / {@code DWB_AI_HOURS} /
 * {@code DWB_AI_DAYS} configure the AI quota shield.</p>
 */
public final class WorkbenchApp extends Application {

    private static final String TITLE = "Belge Tezgâhı · Document Workbench";
    /** {@code src/main/resources/style.css} is packaged at the classpath root. */
    private static final String STYLESHEET = "/style.css";
    private static final double WIDTH = 1_340;
    private static final double HEIGHT = 860;

    private Workbench workbench;
    private WorkspaceManager workspace;
    private GeminiStreamEngine engine;
    private ModelProfile profile;
    private WorkbenchController controller;

    /**
     * Builds the engine before the window exists. Runs on the launcher thread, so it must not touch any JavaFX
     * node; everything created here is plain headless engine state.
     */
    @Override
    public void init() {
        profile = resolveProfile();
        String apiKey = System.getenv("GEMINI_API_KEY");
        engine = apiKey == null || apiKey.isBlank() ? null : new GeminiStreamEngine(apiKey, profile);
        workspace = new WorkspaceManager();
        QuotaManager quota = new QuotaManager(QuotaManager.Config.fromEnvironment(System::getenv),
                org.example.workbench.WorkbenchController.defaultDataDir().resolve("ai-quota.state"));
        workbench = new Workbench(new DocumentParser(), new InvertedIndex(), engine, quota,
                Workbench.DEFAULT_MIN_SCORE);
    }

    /** {@code --model ID} beats {@code DWB_MODEL}, which beats the default profile. */
    private ModelProfile resolveProfile() {
        List<String> raw = getParameters() == null ? List.of() : getParameters().getRaw();
        for (int i = 0; i < raw.size() - 1; i++) {
            if ("--model".equals(raw.get(i))) {
                Optional<ModelProfile> parsed = ModelProfile.parse(raw.get(i + 1));
                if (parsed.isPresent()) {
                    return parsed.get();
                }
            }
        }
        return ModelProfile.parse(System.getenv("DWB_MODEL")).orElse(ModelProfile.DEFAULT);
    }

    @Override
    public void start(Stage stage) {
        controller = new WorkbenchController(workbench, workspace, engine, profile);
        Scene scene = new Scene(controller.view(), WIDTH, HEIGHT);
        URL stylesheet = WorkbenchApp.class.getResource(STYLESHEET);
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        } else {
            System.getLogger(WorkbenchApp.class.getName())
                    .log(System.Logger.Level.WARNING, "Stylesheet " + STYLESHEET + " not on the classpath");
        }
        stage.setTitle(TITLE);
        stage.setMinWidth(960);
        stage.setMinHeight(640);
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> controller.shutdown());
        stage.show();
        controller.onShown(stage);
    }

    /** Releases the watcher thread and the telemetry ticker; the JVM then has no non-daemon work left. */
    @Override
    public void stop() {
        if (controller != null) {
            controller.shutdown();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
