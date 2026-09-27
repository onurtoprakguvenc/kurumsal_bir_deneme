package org.yazi.desktop;

import javafx.application.Application;

/**
 * Entry point. Deliberately does not extend {@link Application}: that lets JavaFX start from a plain classpath
 * without module-path configuration.
 */
public final class Launcher {

    private Launcher() {}

    public static void main(String[] args) {
        Application.launch(YaziApp.class, args);
    }
}
