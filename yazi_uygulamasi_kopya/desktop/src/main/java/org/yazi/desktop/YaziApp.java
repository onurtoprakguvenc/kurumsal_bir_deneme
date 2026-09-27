package org.yazi.desktop;

import javafx.application.Application;
import javafx.stage.Stage;
import org.yazi.gateway.ApiKeys;
import org.yazi.prose.ModelCatalog;

/**
 * JavaFX application: one window, one document. An optional first argument names a file to open, e.g.
 * {@code gradlew :desktop:run --args="notes.txt"} (relative to the repository root).
 */
public final class YaziApp extends Application {

    @Override
    public void start(Stage stage) {
        KeyedGateway gateway = new KeyedGateway(ApiKeys.fromEnvironment().orElse(null));
        MainWindow window = new MainWindow(stage, gateway, ModelCatalog.fromEnvironment(), UiSettings.preferences(),
                UsageMeter.Rates.fromEnvironment());
        stage.setScene(window.buildScene());
        stage.setMinWidth(720);
        stage.setMinHeight(400);
        stage.show();
        getParameters().getUnnamed().stream().findFirst().ifPresent(window::openFromCommandLine);
        window.focusEditor();
    }
}
