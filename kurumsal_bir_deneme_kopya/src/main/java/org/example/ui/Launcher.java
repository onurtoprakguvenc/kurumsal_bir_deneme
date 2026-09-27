package org.example.ui;

import javafx.application.Application;

/**
 * Plain main class for classpath launches ({@code gradlew run}, a fat jar, the IDE). When the class named on the
 * command line itself extends {@link Application}, the Java launcher insists on JavaFX being on the module path and
 * aborts with "JavaFX runtime components are missing"; starting through this class avoids that check.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(WorkbenchApp.class, args);
    }
}
