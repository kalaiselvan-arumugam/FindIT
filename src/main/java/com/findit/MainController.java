package com.findit;

import com.findit.engine.*;
import com.findit.model.FileEntry;
import com.findit.ui.AppTheme;
import com.findit.ui.SettingsController;
import com.findit.util.GlobalHotkeyManager;
import com.findit.util.Settings;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCombination;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.Icon;
import javax.swing.filechooser.FileSystemView;
import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FXML controller for the main Find IT window.
 * Wires the UI to the engine layer; no business logic lives here.
 */
public class MainController {

    private static final Logger LOG = LoggerFactory.getLogger(MainController.class);

    // ── FXML injected ────────────────────────────────────────────────────────
    @FXML private TextField searchField;
    @FXML private Button clearBtn;
    @FXML private ToggleButton matchCaseBtn;
    @FXML private ToggleButton wholeWordBtn;
    @FXML private ToggleButton matchPathBtn;
    @FXML private ToggleButton regexBtn;
    @FXML private Button reindexBtn;
    @FXML private Button settingsBtn;
    @FXML private Label indexingSpinner;
    @FXML private TableView<FileEntry> resultTable;
    @FXML private TableColumn<FileEntry, String> nameCol;
    @FXML private TableColumn<FileEntry, String> pathCol;
    @FXML private TableColumn<FileEntry, Long>   sizeCol;
    @FXML private TableColumn<FileEntry, Long>   dateCol;
    @FXML private Label indexCountLabel;
    @FXML private Label resultCountLabel;
    @FXML private Label stateLabel;

    // ── Engine refs ───────────────────────────────────────────────────────────
    private FileIndexer fileIndexer;
    private SearchEngine searchEngine;
    private FileIndex fileIndex;
    private GlobalHotkeyManager hotkeyManager;

    private static final Map<String, Image> ICON_CACHE = new ConcurrentHashMap<>();
    private static final java.util.Set<String> ICON_LOADING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // ── Other refs ────────────────────────────────────────────────────────────
    private Stage primaryStage;
    private Scene scene;

    // ── Table data ────────────────────────────────────────────────────────────
    private final ObservableList<FileEntry> results = FXCollections.observableArrayList();
    private PauseTransition debounce;

    // ── Dependency injection (called from Main.java) ──────────────────────────
    public void init(Stage stage, Scene scene,
                     FileIndexer fileIndexer, SearchEngine searchEngine,
                     FileIndex fileIndex, GlobalHotkeyManager hotkeyManager) {
        this.primaryStage = stage;
        this.scene = scene;
        this.fileIndexer = fileIndexer;
        this.searchEngine = searchEngine;
        this.fileIndex    = fileIndex;
        this.hotkeyManager = hotkeyManager;
        wireEngine();
    }

    @FXML
    private void initialize() {
        setupTable();
        setupSearchBar();
        setupContextMenu();
        setupKeyboardShortcuts();
    }

    // ── Table Setup ───────────────────────────────────────────────────────────

    private void setupTable() {
        nameCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().name()));
        pathCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().path()));
        sizeCol.setCellValueFactory(c -> new javafx.beans.property.SimpleLongProperty(c.getValue().size()).asObject());
        dateCol.setCellValueFactory(c -> new javafx.beans.property.SimpleLongProperty(c.getValue().lastModified()).asObject());

        // Custom cell renderers
        nameCol.setCellFactory(col -> new TableCell<>() {
            private final ImageView imageView = new ImageView();
            {
                imageView.setFitHeight(16);
                imageView.setFitWidth(16);
            }
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("");
                    return;
                }
                int idx = getIndex();
                if (idx < 0 || getTableView() == null || idx >= getTableView().getItems().size()) {
                    setGraphic(null);
                    return;
                }
                
                FileEntry e = getTableView().getItems().get(idx);
                setText(item);

                if (!com.findit.util.Settings.get().showIcons()) {
                    setGraphic(null);
                    setStyle(e.isDirectory() ? "-fx-text-fill: #dcdcaa;" : "");
                    return;
                }

                String ext = getExtension(e.name());
                String key = e.isDirectory() ? ":DIR:" : ext;
                Image cached = ICON_CACHE.get(key);

                if (cached != null) {
                    imageView.setImage(cached);
                    setGraphic(imageView);
                } else {
                    setGraphic(null);
                    if (ICON_LOADING.add(key)) { // Only one Task per extension at a time
                        java.util.concurrent.CompletableFuture.supplyAsync(() -> getSystemIconFor(e))
                            .thenAccept(icon -> {
                                ICON_LOADING.remove(key);
                                javafx.application.Platform.runLater(() -> {
                                    int currentIdx = getIndex();
                                    if (getTableView() != null && currentIdx >= 0 && currentIdx < getTableView().getItems().size()
                                            && getTableView().getItems().get(currentIdx) == e) {
                                        if (icon != null) {
                                            imageView.setImage(icon);
                                            setGraphic(imageView);
                                        }
                                    }
                                });
                            });
                    }
                }

                setStyle(e.isDirectory() ? "-fx-text-fill: #dcdcaa;" : "");
            }
        });

        pathCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
                setStyle(empty ? "" : "-fx-text-fill: #9cdcfe;");
            }
        });

        sizeCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(Long item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); return; }
                FileEntry e = getTableView().getItems().get(getIndex());
                setText(e.formattedSize());
                setStyle("-fx-alignment: CENTER_RIGHT; -fx-text-fill: #ce9178;");
            }
        });

        dateCol.setCellFactory(col -> new TableCell<>() {
            private final SimpleDateFormat fmt = new SimpleDateFormat("dd MMM yyyy HH:mm");
            @Override protected void updateItem(Long item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null || item == 0 ? null : fmt.format(new Date(item)));
                setStyle(empty ? "" : "-fx-text-fill: #b5cea8;");
            }
        });

        // Bind sorted list to table
        SortedList<FileEntry> sorted = new SortedList<>(results);
        sorted.comparatorProperty().bind(resultTable.comparatorProperty());
        resultTable.setItems(sorted);

        // Double-click to open
        resultTable.setRowFactory(tv -> {
            TableRow<FileEntry> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) openEntry(row.getItem());
            });
            return row;
        });
    }

    private Image getSystemIconFor(FileEntry e) {
        String ext = getExtension(e.name());
        String key = e.isDirectory() ? ":DIR:" : (ext.isEmpty() ? ".tmp" : ext);

        return ICON_CACHE.computeIfAbsent(key, k -> {
            try {
                File f;
                if (e.isDirectory()) {
                    f = new File(System.getProperty("user.home"));
                } else {
                    // Reuse a single file per extension to avoid thousands of disk writes
                    f = new File(System.getProperty("java.io.tmpdir"), "findit_icon_ref" + key);
                    if (!f.exists()) {
                        f.createNewFile();
                        f.deleteOnExit();
                    }
                }

                Icon swingIcon = FileSystemView.getFileSystemView().getSystemIcon(f);
                if (swingIcon != null) {
                    BufferedImage img = new BufferedImage(
                            swingIcon.getIconWidth(), swingIcon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
                    swingIcon.paintIcon(null, img.getGraphics(), 0, 0);
                    return javafx.embed.swing.SwingFXUtils.toFXImage(img, null);
                }
            } catch (Exception ignored) {}
            return null;
        });
    }

    private String getExtension(String name) {
        int idx = name.lastIndexOf('.');
        return idx > 0 ? name.substring(idx) : "";
    }

    // ── Search Bar ────────────────────────────────────────────────────────────

    private void setupSearchBar() {
        clearBtn.setOnAction(e -> searchField.clear());
        searchField.textProperty().addListener((obs, o, n) -> {
            clearBtn.setVisible(!n.isEmpty());
            clearBtn.setManaged(!n.isEmpty());
            if (n.isEmpty()) {
                if (debounce != null) debounce.stop();
                results.clear();
                resultCountLabel.setText("0 results");
            } else {
                scheduleSearch();
            }
        });
        Platform.runLater(searchField::requestFocus);
    }

    private void wireEngine() {
        int debounceMs = Settings.get().debounceMs();
        debounce = new PauseTransition(Duration.millis(debounceMs));
        debounce.setOnFinished(e -> runSearch());

        reindexBtn.setOnAction(e -> {
            setSpinner(true);
            setStateLabel("Indexing...");
            fileIndexer.reindex();
        });

        settingsBtn.setOnAction(e -> openSettings());

        fileIndexer.onProgress(n -> Platform.runLater(() -> {
            setSpinner(true);
            stateLabel.setText("Scanning: " + String.format("%,d", n) + " files found so far…");
        }));

        fileIndexer.onDirScanned(dir -> Platform.runLater(() -> {
            // Keep it brief to prevent UI lockup/flicker
            String brief = dir.length() > 60 ? "..." + dir.substring(dir.length() - 57) : dir;
            stateLabel.setText("Scanning: " + brief);
        }));

        fileIndexer.onDone(() -> Platform.runLater(() -> {
            setSpinner(false);
            updateIndexCount(fileIndex.size());
            stateLabel.setText("Watching (" + String.format("%,d", fileIndex.size()) + " files)");
        }));
    }

    private void scheduleSearch() {
        if (debounce != null) { debounce.stop(); debounce.playFromStart(); }
    }

    private void runSearch() {
        String query = searchField.getText();
        if (query.isBlank()) return;

        int limit = Math.min(5000, Settings.get().maxResults());

        searchEngine.search(
                query,
                matchCaseBtn.isSelected(),
                wholeWordBtn.isSelected(),
                matchPathBtn.isSelected(),
                regexBtn.isSelected(),
                limit,
                (found, duration) -> Platform.runLater(() -> {
                    results.setAll(found);
                    resultCountLabel.setText(String.format("%,d result%s (%,d ms)",
                            found.size(), found.size() == 1 ? "" : "s", duration)
                            + (found.size() >= limit ? " (capped)" : ""));
                }));
    }

    // ── Context Menu ──────────────────────────────────────────────────────────

    private void setupContextMenu() {
        MenuItem openItem    = new MenuItem("Open");
        MenuItem openFolderItem = new MenuItem("Open Containing Folder");
        MenuItem copyPathItem = new MenuItem("Copy Path");
        MenuItem copyNameItem = new MenuItem("Copy Name");
        ContextMenu menu = new ContextMenu(openItem, openFolderItem,
                new SeparatorMenuItem(), copyPathItem, copyNameItem);

        openItem.setOnAction(e -> { FileEntry entry = resultTable.getSelectionModel().getSelectedItem(); if (entry != null) openEntry(entry); });
        openFolderItem.setOnAction(e -> { FileEntry entry = resultTable.getSelectionModel().getSelectedItem(); if (entry != null) openFolder(entry); });
        copyPathItem.setOnAction(e -> { FileEntry entry = resultTable.getSelectionModel().getSelectedItem(); if (entry != null) copyToClipboard(entry.path()); });
        copyNameItem.setOnAction(e -> { FileEntry entry = resultTable.getSelectionModel().getSelectedItem(); if (entry != null) copyToClipboard(entry.name()); });

        resultTable.setContextMenu(menu);
    }

    // ── Keyboard shortcuts ────────────────────────────────────────────────────

    private void setupKeyboardShortcuts() {
        // Alt+C, Alt+W, Alt+P, Alt+R for toggles
        searchField.sceneProperty().addListener((obs, o, scene) -> {
            if (scene == null) return;
            scene.getAccelerators().put(KeyCombination.valueOf("Alt+C"), () -> matchCaseBtn.setSelected(!matchCaseBtn.isSelected()));
            scene.getAccelerators().put(KeyCombination.valueOf("Alt+W"), () -> wholeWordBtn.setSelected(!wholeWordBtn.isSelected()));
            scene.getAccelerators().put(KeyCombination.valueOf("Alt+P"), () -> matchPathBtn.setSelected(!matchPathBtn.isSelected()));
            scene.getAccelerators().put(KeyCombination.valueOf("Alt+R"), () -> regexBtn.setSelected(!regexBtn.isSelected()));
        });
        // Toggle buttons trigger re-search
        for (ToggleButton btn : List.of(matchCaseBtn, wholeWordBtn, matchPathBtn, regexBtn)) {
            btn.selectedProperty().addListener((obs, o, n) -> runSearch());
        }
    }

    // ── Settings Dialog ───────────────────────────────────────────────────────

    private void openSettings() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/findit/ui/SettingsDialog.fxml"));
            Scene dialogScene = new Scene(loader.load(), 560, 520);
            AppTheme.apply(dialogScene);

            Stage dialog = new Stage();
            dialog.setTitle("Options — FindIT");
            try (java.io.InputStream is = getClass().getResourceAsStream("/com/findit/icons/app-icon.png")) {
                if (is != null) dialog.getIcons().add(new javafx.scene.image.Image(is));
            } catch (Exception ignored) {}
            dialog.initOwner(primaryStage);
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.setScene(dialogScene);

            SettingsController ctrl = loader.getController();
            ctrl.init(dialog, hotkeyManager, () -> AppTheme.apply(scene));
            dialog.showAndWait();

            // Debounce may have changed
            debounce.setDuration(Duration.millis(Settings.get().debounceMs()));
        } catch (Exception e) {
            LOG.error("Failed to open settings", e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void openEntry(FileEntry entry) {
        try { Desktop.getDesktop().open(new File(entry.path())); }
        catch (Exception e) { LOG.warn("Cannot open {}: {}", entry.path(), e.getMessage()); }
    }

    private void openFolder(FileEntry entry) {
        try {
            File f = new File(entry.path());
            File dir = entry.isDirectory() ? f : f.getParentFile();
            if (dir != null) Desktop.getDesktop().open(dir);
        } catch (Exception e) { LOG.warn("Cannot open folder for {}: {}", entry.path(), e.getMessage()); }
    }

    private void copyToClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    // ── Public status update API (called from Main.java) ──────────────────────

    public void setStateLabel(String text) {
        Platform.runLater(() -> stateLabel.setText(text));
    }

    public void updateIndexCount(int count) {
        Platform.runLater(() -> indexCountLabel.setText(String.format("%,d files in index", count)));
    }

    public void setSpinner(boolean visible) {
        Platform.runLater(() -> {
            indexingSpinner.setVisible(visible);
            indexingSpinner.setManaged(visible);
        });
    }
}
