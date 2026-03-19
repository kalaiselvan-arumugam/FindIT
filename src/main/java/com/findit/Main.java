package com.findit;

import com.findit.engine.*;
import com.findit.persistence.IndexStore;
import com.findit.ui.AppTheme;
import com.findit.util.GlobalHotkeyManager;
import com.findit.util.Settings;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * JavaFX Application entry point for FindIT.
 * All heavy work (index load, watcher start, re-index) is performed
 * on daemon background threads; the FX thread is never blocked.
 */
public class Main extends Application {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private FileIndex fileIndex;
    private IndexStore indexStore;
    private FileWatcher fileWatcher;
    private FileIndexer fileIndexer;
    private DriveMonitor driveMonitor;
    private SearchEngine searchEngine;
    private GlobalHotkeyManager hotkeyManager;
    private MainController controller;
    private Stage primaryStage;

    @Override
    public void start(Stage stage) throws Exception {
        this.primaryStage = stage;

        // ── Build engine objects ───────────────────────────────────────────
        fileIndex    = new FileIndex();
        indexStore   = new IndexStore();
        // Break circular dependency: create both, then wire together via setters
        fileWatcher  = new FileWatcher(fileIndex, indexStore);
        fileIndexer  = new FileIndexer(fileIndex, indexStore);
        fileWatcher.setIndexer(fileIndexer);
        fileIndexer.setFileWatcher(fileWatcher);
        driveMonitor = new DriveMonitor(fileIndex, fileIndexer, indexStore);
        searchEngine = new SearchEngine(fileIndex);

        // ── Load FXML + show window immediately ────────────────────────────
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/findit/ui/MainWindow.fxml"));
        Parent root = loader.load();
        Scene scene = new Scene(root, 1200, 720);
        AppTheme.apply(scene);

        stage.setTitle("FindIT");
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setScene(scene);
        stage.setMinWidth(800);
        stage.setMinHeight(480);
        loadIcon(stage);

        // Handle close via tray
        stage.setOnCloseRequest(e -> {
            if (Settings.get().startMinimized() && SystemTray.isSupported()) {
                e.consume();
                Platform.runLater(stage::hide);
            } else {
                shutdown();
            }
        });

        if (Settings.get().startMinimized() && SystemTray.isSupported()) {
            LOG.info("Starting minimized to system tray");
        } else {
            stage.show();
        }

        // ── Wire controller ────────────────────────────────────────────────
        controller = loader.getController();
        hotkeyManager = new GlobalHotkeyManager(this::showWindow);
        controller.init(stage, scene, fileIndexer, searchEngine, fileIndex, hotkeyManager);
        controller.setStateLabel("Loading index…");

        // ── Non-blocking startup sequence ──────────────────────────────────
        CompletableFuture.runAsync(this::startupSequence,
                Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "findit-startup");
                    t.setDaemon(true);
                    return t;
                }));

        // ── System tray + hotkey ───────────────────────────────────────────
        setupSystemTray(stage);
        hotkeyManager.apply();
    }

    /** Non-blocking startup: load SQLite → start watcher → auto-reindex if empty. */
    private void startupSequence() {
        try {
            // 1. Load persisted index
            List<com.findit.model.FileEntry> loaded = indexStore.load();
            fileIndex.addAll(loaded);
            LOG.info("Loaded {} entries from SQLite", loaded.size());

            Platform.runLater(() -> {
                controller.updateIndexCount(fileIndex.size());
                controller.setStateLabel(loaded.isEmpty() ? "Starting first-time index…" : "Ready");
            });

            // 2. Start file watcher (always-on for entire app lifetime)
            if (Settings.get().watcherEnabled()) {
                List<String> dirs = fileIndex.getAllDirectoryPaths();
                fileWatcher.start(dirs.isEmpty()
                        ? getDefaultRootDirs()  // at minimum watch roots
                        : dirs);
                Platform.runLater(() -> controller.setStateLabel("Watching"));
            }

            // 3. If no previous index exists or it's suspiciously small (interrupted first run),
            //    trigger a fresh index automatically.
            if (loaded.size() < 1000) {
                Platform.runLater(() -> controller.setSpinner(true));
                fileIndexer.reindex();
            }

            // 4. Start drive monitor to watch for newly inserted USBs
            driveMonitor.start();

        } catch (Exception e) {
            LOG.error("Startup sequence failed", e);
            Platform.runLater(() -> controller.setStateLabel("Error — see logs"));
        }
    }

    /** Returns string list of root directories for initial watcher seed. */
    private List<String> getDefaultRootDirs() {
        return DriveMonitor.detectAllRoots();
    }

    // ── System Tray ───────────────────────────────────────────────────────────

    private void setupSystemTray(Stage stage) {
        if (!SystemTray.isSupported()) return;
        Platform.setImplicitExit(false); // keep app alive when window hidden

        try {
            SystemTray tray = SystemTray.getSystemTray();
            java.awt.Image trayIcon = loadAwtImage();
            if (trayIcon == null) {
                trayIcon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            }

            PopupMenu popup = new PopupMenu();
            MenuItem openItem    = new MenuItem("Open FindIT");
            MenuItem reindexItem = new MenuItem("Re-index");
            MenuItem exitItem    = new MenuItem("Exit");

            openItem.addActionListener(e -> Platform.runLater(this::showWindow));
            reindexItem.addActionListener(e -> {
                Platform.runLater(this::showWindow);
                fileIndexer.reindex();
            });
            exitItem.addActionListener(e -> shutdown());

            popup.add(openItem);
            popup.add(reindexItem);
            popup.addSeparator();
            popup.add(exitItem);

            TrayIcon icon = new TrayIcon(trayIcon, "FindIT", popup);
            icon.setImageAutoSize(true);
            icon.addActionListener(e -> Platform.runLater(this::showWindow));
            tray.add(icon);
        } catch (Exception e) {
            LOG.warn("System tray setup failed: {}", e.getMessage());
        }
    }

    private void showWindow() {
        if (primaryStage != null) {
            primaryStage.show();
            primaryStage.toFront();
            primaryStage.requestFocus();
        }
    }

    private void loadIcon(Stage stage) {
        try (InputStream is = getClass().getResourceAsStream("/com/findit/icons/app-icon.png")) {
            if (is != null) stage.getIcons().add(new Image(is));
        } catch (Exception ignored) {}
    }

    private java.awt.Image loadAwtImage() {
        try (InputStream is = getClass().getResourceAsStream("/com/findit/icons/app-icon.png")) {
            if (is != null) return ImageIO.read(is);
        } catch (Exception ignored) {}
        return null;
    }

    private void shutdown() {
        LOG.info("Shutting down FindIT");
        if (indexStore != null) indexStore.close();
        Platform.exit();
        System.exit(0);
    }

    @Override
    public void stop() {
        if (hotkeyManager != null) hotkeyManager.unregister();
        if (driveMonitor != null) driveMonitor.stop();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
