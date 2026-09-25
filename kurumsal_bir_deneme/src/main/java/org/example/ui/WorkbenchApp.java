package org.example.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.example.admin.CentralPolicy;
import org.example.core.AnswerModel;
import org.example.ingest.DocumentParser;
import org.example.llm.GeminiStreamEngine;
import org.example.llm.ModelProfile;
import org.example.p2p.LanSyncService;
import org.example.quota.QuotaManager;
import org.example.workbench.ExtendedWorkbenchController;
import org.example.workbench.WorkbenchController;

import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * JavaFX entry point of the Document Workbench: builds the {@link ExtendedWorkbenchController}, mounts the
 * {@link WorkbenchChassis} with {@code style.css}, and opens the default (or most recent) project in the background
 * so the window appears immediately.
 *
 * <p>Run through {@code gradlew run} (512 MB heap, see {@code build.gradle}) or {@link Launcher}. Environment:
 * {@code GEMINI_API_KEY} enables grounded answers, {@code DWB_MODEL} picks a model, {@code DWB_HOME} overrides the
 * workspace directory, {@code DWB_ADMIN_TOKEN} enables {@code admin unlock}; web search is configured by
 * {@code WebSearchBridge.Config#fromEnvironment}.</p>
 */
public final class WorkbenchApp extends Application {

    private static final String TITLE = "Belge Tezgâhı · Document Workbench";
    /** {@code src/main/resources/style.css} sits at the classpath root. */
    private static final String[] STYLESHEETS = {"/style.css", "/resources/style.css"};
    private static final double WIDTH = 1_280;
    private static final double HEIGHT = 820;

    private ExtendedWorkbenchController ext;
    private WorkbenchChassis chassis;

    /** Headless engine construction on the launcher thread; no JavaFX node is touched here. */
    @Override
    public void init() {
        // IT's machine-wide policy (if any) overrides environment variables and local settings.
        CentralPolicy policy = CentralPolicy.loadDefault();
        ModelProfile profile = resolveProfile();
        String apiKey = System.getenv("GEMINI_API_KEY");
        AnswerModel model = !policy.aiAllowed() || apiKey == null || apiKey.isBlank() ? null
                : new GeminiStreamEngine(apiKey, profile);
        Optional<AnswerModel> answering = Optional.ofNullable(model);

        String home = System.getenv("DWB_HOME");
        Path workspace = home == null || home.isBlank() ? WorkbenchController.defaultDataDir() : Path.of(home);
        ExtendedWorkbenchController.Services services = new ExtendedWorkbenchController.Services(
                new DocumentParser(), new QuotaManager(QuotaManager.Config.fromEnvironment(
                policy.environment(System::getenv)), workspace.resolve("ai-quota.state")),
                () -> answering, null, null, null, System.getenv("DWB_ADMIN_TOKEN"), policy);
        ext = new ExtendedWorkbenchController(ExtendedWorkbenchController.Config.defaults(workspace, policy),
                services);
        enableLanSync(policy, workspace);
    }

    /**
     * LAN sync with the other Document Workbench nodes on this network ({@code DWB_LAN=off} disables it for this
     * process; the central policy and {@code > lan --off} are checked by the controller). Network settings:
     * {@code DWB_SECRET}, {@code DWB_NAME}, {@code DWB_LAN_SEEDS}, see {@link LanSyncService.Settings#fromEnvironment}.
     */
    private void enableLanSync(CentralPolicy policy, Path workspace) {
        java.util.function.UnaryOperator<String> env = policy.environment(System::getenv);
        if ("off".equalsIgnoreCase(String.valueOf(env.apply("DWB_LAN")).strip())) {
            return;
        }
        try {
            ext.enableLanSync(LanSyncService.Settings.fromEnvironment(workspace, env));
        } catch (java.io.IOException | IllegalArgumentException e) {
            System.getLogger(WorkbenchApp.class.getName()).log(System.Logger.Level.WARNING,
                    "LAN sync disabled: " + e.getMessage());
        }
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
        String env = System.getenv("DWB_MODEL");
        Optional<ModelProfile> fromEnv = ModelProfile.parse(env);
        if (fromEnv.isEmpty() && env != null && !env.isBlank()) {
            // The console runner refuses an unknown DWB_MODEL; the window still opens, but says why it ignored it.
            System.getLogger(WorkbenchApp.class.getName()).log(System.Logger.Level.WARNING,
                    "DWB_MODEL=" + env + " is not a known model (" + ModelProfile.ids() + "); using "
                            + ModelProfile.DEFAULT.id());
        }
        return fromEnv.orElse(ModelProfile.DEFAULT);
    }

    @Override
    public void start(Stage stage) {
        chassis = new WorkbenchChassis(ext, stage);
        Scene scene = new Scene(chassis.root(), WIDTH, HEIGHT);
        mountStylesheet(scene);
        chassis.install(scene);

        stage.setTitle(TITLE);
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        stage.setScene(scene);
        stage.show();

        // Loading the snapshot and warming the index may take a moment on old disks; the chassis binds itself when
        // the project activates, so the window is responsive meanwhile.
        Thread.ofVirtual().name("dwb-startup").start(() -> {
            try {
                chassis.startupFinished(ext.start());
            } catch (Exception | OutOfMemoryError e) {
                chassis.startupFailed(e);
            }
        });
    }

    private static void mountStylesheet(Scene scene) {
        for (String candidate : STYLESHEETS) {
            URL url = WorkbenchApp.class.getResource(candidate);
            if (url != null) {
                scene.getStylesheets().add(url.toExternalForm());
                return;
            }
        }
        System.getLogger(WorkbenchApp.class.getName())
                .log(System.Logger.Level.WARNING, "style.css not found on the classpath; using the default theme");
    }

    /** Unbinds the UI, then saves the active project and releases every engine thread. */
    @Override
    public void stop() {
        if (chassis != null) {
            chassis.close();
        }
        if (ext != null) {
            ext.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
