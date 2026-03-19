package com.findit.ui;

import com.findit.util.GlobalHotkeyManager;
import com.findit.util.Settings;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.controlsfx.control.ToggleSwitch;

import java.io.File;
import java.net.URI;

/**
 * Controller for SettingsDialog.fxml — frameless, dropshadow, left-nav, ControlsFX ToggleSwitch.
 */
public class SettingsController {

    // ── General page fields ───────────────────────────────────────────────────
    @FXML private ComboBox<String> themeCombo;
    @FXML private TextField        maxResultsField;
    @FXML private TextField        debounceField;
    @FXML private ToggleSwitch     watcherEnabledCheck;
    @FXML private ToggleSwitch     hotkeyEnabledCheck;
    @FXML private TextField        hotkeyComboField;
    @FXML private ToggleSwitch     startMinimizedCheck;
    @FXML private ToggleSwitch     showIconsCheck;

    // ── Exclusions page ───────────────────────────────────────────────────────
    @FXML private TextArea excludePathsArea;
    @FXML private Button   addExcludeBtn;
    @FXML private Button   removeExcludeBtn;

    // ── About page ────────────────────────────────────────────────────────────
    @FXML private Hyperlink githubLink;

    // ── Footer ────────────────────────────────────────────────────────────────
    @FXML private Button cancelBtn;
    @FXML private Button saveBtn;

    // ── Title bar (frameless) ─────────────────────────────────────────────────
    @FXML private HBox   settingsTitleBar;
    @FXML private Button settingsCloseBtn;

    // ── Left nav ──────────────────────────────────────────────────────────────
    @FXML private Button navGeneral;
    @FXML private Button navExclusions;
    @FXML private Button navAbout;

    // ── Pages (StackPane children) ────────────────────────────────────────────
    @FXML private VBox pageGeneral;
    @FXML private VBox pageExclusions;
    @FXML private VBox pageAbout;

    private Stage               stage;
    private GlobalHotkeyManager hotkeyManager;
    private Runnable            onThemeChanged;
    private double              dragOffsetX, dragOffsetY;

    @FXML
    private void initialize() {
        themeCombo.getItems().addAll("dark", "light");
        loadValues();

        // Sidebar navigation
        wireNav(navGeneral,    pageGeneral);
        wireNav(navExclusions, pageExclusions);
        wireNav(navAbout,      pageAbout);

        // Footer buttons
        if (cancelBtn != null) cancelBtn.setOnAction(e -> { if (stage != null) stage.close(); });
        if (saveBtn   != null) saveBtn.setOnAction(e -> save());

        // Add path exclusion
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

        // GitHub hyperlink
        if (githubLink != null) {
            githubLink.setOnAction(e -> {
                try {
                    java.awt.Desktop.getDesktop().browse(new URI("https://github.com/kalaiselvan-arumugam/FindIT"));
                } catch (Exception ignored) {}
            });
        }

        // Title bar drag
        if (settingsTitleBar != null) {
            settingsTitleBar.setOnMousePressed(e -> {
                if (stage != null) {
                    dragOffsetX = e.getScreenX() - stage.getX();
                    dragOffsetY = e.getScreenY() - stage.getY();
                }
            });
            settingsTitleBar.setOnMouseDragged(e -> {
                if (stage != null) {
                    stage.setX(e.getScreenX() - dragOffsetX);
                    stage.setY(e.getScreenY() - dragOffsetY);
                }
            });
        }

        // Close button
        if (settingsCloseBtn != null) {
            settingsCloseBtn.setOnAction(e -> {
                if (stage != null)
                    stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
            });
        }
    }

    /** Show the target page; highlight the clicked nav item. */
    private void wireNav(Button navBtn, VBox page) {
        if (navBtn == null || page == null) return;
        navBtn.setOnAction(e -> {
            for (VBox p : new VBox[]{pageGeneral, pageExclusions, pageAbout}) {
                if (p != null) { p.setVisible(false); p.setManaged(false); }
            }
            for (Button b : new Button[]{navGeneral, navExclusions, navAbout}) {
                if (b != null) b.getStyleClass().remove("settings-nav-active");
            }
            page.setVisible(true);
            page.setManaged(true);
            navBtn.getStyleClass().add("settings-nav-active");
        });
    }

    /** Called by MainController after FXML is loaded. */
    public void init(Stage stage, GlobalHotkeyManager hotkeyManager, Runnable onThemeChanged) {
        this.stage           = stage;
        this.hotkeyManager   = hotkeyManager;
        this.onThemeChanged  = onThemeChanged;
        loadValues();
    }

    private void loadValues() {
        Settings s = Settings.get();
        if (themeCombo          != null) themeCombo.setValue(s.theme());
        if (maxResultsField     != null) maxResultsField.setText(String.valueOf(s.maxResults()));
        if (debounceField       != null) debounceField.setText(String.valueOf(s.debounceMs()));
        if (watcherEnabledCheck != null) watcherEnabledCheck.setSelected(s.watcherEnabled());
        if (hotkeyEnabledCheck  != null) hotkeyEnabledCheck.setSelected(s.hotkeyEnabled());
        if (hotkeyComboField    != null) hotkeyComboField.setText(s.hotkeyCombo());
        if (startMinimizedCheck != null) startMinimizedCheck.setSelected(s.startMinimized());
        if (showIconsCheck      != null) showIconsCheck.setSelected(s.showIcons());

        String excludes = s.excludePaths();
        if (excludePathsArea != null)
            excludePathsArea.setText(excludes.isEmpty() ? "" : excludes.replace(",", "\n"));
    }

    private void save() {
        Settings s = Settings.get();
        if (themeCombo != null && themeCombo.getValue() != null) s.theme(themeCombo.getValue());
        if (maxResultsField != null) {
            try { s.maxResults(Integer.parseInt(maxResultsField.getText().trim())); }
            catch (NumberFormatException ignored) {}
        }
        if (debounceField != null) {
            try { s.debounceMs(Integer.parseInt(debounceField.getText().trim())); }
            catch (NumberFormatException ignored) {}
        }
        if (watcherEnabledCheck != null)  s.watcherEnabled(watcherEnabledCheck.isSelected());
        if (hotkeyEnabledCheck  != null)  s.hotkeyEnabled(hotkeyEnabledCheck.isSelected());
        if (startMinimizedCheck != null)  s.startMinimized(startMinimizedCheck.isSelected());
        if (showIconsCheck      != null)  s.showIcons(showIconsCheck.isSelected());
        if (hotkeyComboField    != null)  s.hotkeyCombo(hotkeyComboField.getText().trim().toUpperCase());
        if (excludePathsArea    != null)  s.excludePaths(excludePathsArea.getText().trim().replace("\n", ","));

        s.flush();
        if (hotkeyManager   != null) hotkeyManager.apply();
        if (onThemeChanged  != null) onThemeChanged.run();
        if (stage           != null) stage.close();
    }
}
