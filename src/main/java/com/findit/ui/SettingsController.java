package com.findit.ui;

import com.findit.util.GlobalHotkeyManager;
import com.findit.util.Settings;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.net.URI;

/**
 * Controller for the Settings / Options dialog (SettingsDialog.fxml).
 * Uses fully-qualified javafx.scene.control types to avoid AWT ambiguity.
 */
public class SettingsController {

    @FXML private ComboBox<String> themeCombo;
    @FXML private TextField maxResultsField;
    @FXML private TextField debounceField;
    @FXML private CheckBox watcherEnabledCheck;
    @FXML private CheckBox hotkeyEnabledCheck;
    @FXML private TextField hotkeyComboField;
    @FXML private CheckBox startMinimizedCheck;
    @FXML private CheckBox showIconsCheck;
    @FXML private TextArea excludePathsArea;
    @FXML private Button addExcludeBtn;
    @FXML private Button removeExcludeBtn;
    @FXML private Button cancelBtn;
    @FXML private Button saveBtn;
    @FXML private Hyperlink githubLink;

    private Stage stage;
    private GlobalHotkeyManager hotkeyManager;
    private Runnable onThemeChanged;

    @FXML
    private void initialize() {
        themeCombo.getItems().addAll("dark", "light");
        loadValues();

        if (cancelBtn != null) cancelBtn.setOnAction(e -> { if (stage != null) stage.close(); });
        if (saveBtn   != null) saveBtn.setOnAction(e -> save());

        if (addExcludeBtn != null) {
            addExcludeBtn.setOnAction(e -> {
                DirectoryChooser chooser = new DirectoryChooser();
                chooser.setTitle("Select Path to Exclude");
                File dir = chooser.showDialog(stage);
                if (dir != null && excludePathsArea != null) {
                    String current = excludePathsArea.getText().trim();
                    excludePathsArea.setText(current.isEmpty() ? dir.getAbsolutePath()
                            : current + "\n" + dir.getAbsolutePath());
                }
            });
        }

        if (githubLink != null) {
            githubLink.setOnAction(e -> {
                try {
                    java.awt.Desktop.getDesktop().browse(new URI("https://github.com/kalaiselvan-arumugam/FindIT"));
                } catch (Exception ignored) {}
            });
        }
    }

    public void init(Stage stage, GlobalHotkeyManager hotkeyManager, Runnable onThemeChanged) {
        this.stage = stage;
        this.hotkeyManager = hotkeyManager;
        this.onThemeChanged = onThemeChanged;
        loadValues();
    }

    private void loadValues() {
        Settings s = Settings.get();
        if (themeCombo != null)           themeCombo.setValue(s.theme());
        if (maxResultsField != null)      maxResultsField.setText(String.valueOf(s.maxResults()));
        if (debounceField != null)        debounceField.setText(String.valueOf(s.debounceMs()));
        if (watcherEnabledCheck != null)  watcherEnabledCheck.setSelected(s.watcherEnabled());
        if (hotkeyEnabledCheck != null)   hotkeyEnabledCheck.setSelected(s.hotkeyEnabled());
        if (hotkeyComboField != null)     hotkeyComboField.setText(s.hotkeyCombo());
        if (startMinimizedCheck != null)  startMinimizedCheck.setSelected(s.startMinimized());
        if (showIconsCheck != null)       showIconsCheck.setSelected(s.showIcons());

        String excludes = s.excludePaths();
        if (excludePathsArea != null) excludePathsArea.setText(excludes.isEmpty() ? "" : excludes.replace(",", "\n"));
    }

    private void save() {
        Settings s = Settings.get();

        if (themeCombo != null && themeCombo.getValue() != null) s.theme(themeCombo.getValue());
        if (maxResultsField != null) {
            try { s.maxResults(Integer.parseInt(maxResultsField.getText().trim())); } catch (NumberFormatException ignored) {}
        }
        if (debounceField != null) {
            try { s.debounceMs(Integer.parseInt(debounceField.getText().trim())); } catch (NumberFormatException ignored) {}
        }
        if (watcherEnabledCheck != null)  s.watcherEnabled(watcherEnabledCheck.isSelected());
        if (hotkeyEnabledCheck != null)   s.hotkeyEnabled(hotkeyEnabledCheck.isSelected());
        if (startMinimizedCheck != null)  s.startMinimized(startMinimizedCheck.isSelected());
        if (showIconsCheck != null)       s.showIcons(showIconsCheck.isSelected());
        if (hotkeyComboField != null) {
            String combo = hotkeyComboField.getText().trim().toUpperCase();
            s.hotkeyCombo(combo);
        }
        if (excludePathsArea != null) {
            s.excludePaths(excludePathsArea.getText().trim().replace("\n", ","));
        }

        s.flush();

        // Re-apply hotkey live without restart
        if (hotkeyManager != null) hotkeyManager.apply();

        // Re-apply theme live
        if (onThemeChanged != null) onThemeChanged.run();

        if (stage != null) stage.close();
    }
}
