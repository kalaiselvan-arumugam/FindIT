package com.findit.ui;

import javafx.scene.Scene;

/**
 * Manages theme application — currently supports "dark" and "light".
 */
public final class AppTheme {

    public static final String DARK_CSS  = "/com/findit/styles/dark.css";
    public static final String LIGHT_CSS = "/com/findit/styles/light.css";

    private AppTheme() {}

    public static void apply(Scene scene, String theme) {
        scene.getStylesheets().clear();
        String css = "light".equalsIgnoreCase(theme) ? LIGHT_CSS : DARK_CSS;
        var url = AppTheme.class.getResource(css);
        if (url != null) scene.getStylesheets().add(url.toExternalForm());
    }

    public static void apply(Scene scene) {
        apply(scene, com.findit.util.Settings.get().theme());
    }
}
