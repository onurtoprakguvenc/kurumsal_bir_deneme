package org.yazi.desktop;

import javafx.application.Application;
import javafx.stage.Stage;
import org.yazi.gateway.ApiKeys;
import org.yazi.prose.ModelCatalog;

/** JavaFX application: one window, one document. */
public final class YaziApp extends Application {

    @Override
    public void start(Stage stage) {
        KeyedGateway gateway = new KeyedGateway(ApiKeys.fromEnvironment().orElse(null));
        MainWindow window = new MainWindow(stage, gateway, ModelCatalog.fromEnvironment());
        stage.setScene(window.buildScene());
        stage.setMinWidth(640);
        stage.setMinHeight(400);
        stage.show();
        window.focusEditor();
    }
}
