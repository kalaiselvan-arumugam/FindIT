package com.findit;

/**
 * Non-JavaFX entry point for the fat JAR.
 *
 * <p>When java -jar runs a class that directly extends
 * {@link javafx.application.Application}, the JVM checks for the JavaFX
 * runtime modules BEFORE the class is loaded and fails with
 * "JavaFX runtime components are missing".
 *
 * <p>This launcher does NOT extend Application, so the module check is
 * bypassed. It simply delegates to {@link Main#main(String[])}, which
 * calls {@link javafx.application.Application#launch} internally and
 * that works fine because JavaFX JARs are on the classpath.
 */
public class Launcher {
    public static void main(String[] args) {
        Main.main(args);
    }
}
