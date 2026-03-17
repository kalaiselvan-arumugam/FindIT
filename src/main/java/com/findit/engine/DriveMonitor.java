package com.findit.engine;

import com.findit.persistence.IndexStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Auto-detects mounted drives/volumes and polls for new/removed removable media.
 *
 * <p>Cross-platform:
 * <ul>
 *   <li>Windows — scans {@code File.listRoots()} (all drive letters, including USB)</li>
 *   <li>Linux   — scans {@code File.listRoots()} + children of {@code /media} and {@code /mnt}</li>
 *   <li>macOS   — scans {@code File.listRoots()} + children of {@code /Volumes}</li>
 * </ul>
 *
 * <p>Polls every {@value #POLL_SECONDS} seconds; on change triggers incremental re-index.
 */
public class DriveMonitor {

    private static final Logger LOG = LoggerFactory.getLogger(DriveMonitor.class);
    private static final int POLL_SECONDS = 5;

    private final FileIndex fileIndex;
    private final FileIndexer fileIndexer;
    private final IndexStore indexStore;

    private final Set<String> knownRoots = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "findit-drive-monitor");
        t.setDaemon(true);
        return t;
    });

    /** Callback invoked on JavaFX thread when a new drive is found (passes root path). */
    private Consumer<String> onDriveAdded;
    /** Callback invoked on JavaFX thread when a drive is removed (passes root path). */
    private Consumer<String> onDriveRemoved;

    public DriveMonitor(FileIndex fileIndex, FileIndexer fileIndexer, IndexStore indexStore) {
        this.fileIndex   = fileIndex;
        this.fileIndexer = fileIndexer;
        this.indexStore  = indexStore;
    }

    public void onDriveAdded(Consumer<String> cb)   { this.onDriveAdded   = cb; }
    public void onDriveRemoved(Consumer<String> cb) { this.onDriveRemoved = cb; }

    /** Returns all readable root paths on this platform right now. */
    public static List<String> detectAllRoots() {
        List<String> roots = new ArrayList<>();
        for (File f : File.listRoots()) {
            if (f.exists() && f.canRead()) roots.add(f.getAbsolutePath());
        }
        if (!isWindows()) {
            addChildren(roots, "/media");    // Linux removable
            addChildren(roots, "/mnt");      // Linux manual mounts
            addChildren(roots, "/Volumes");  // macOS
        }
        return roots;
    }

    /** Start monitoring. Seeds known roots from the detected set at time of call. */
    public void start() {
        knownRoots.addAll(detectAllRoots());
        LOG.info("DriveMonitor started — tracking {} roots", knownRoots.size());
        scheduler.scheduleWithFixedDelay(this::poll, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
    }

    public void stop() { scheduler.shutdownNow(); }

    // ── internals ─────────────────────────────────────────────────────────────

    private void poll() {
        List<String> current = detectAllRoots();

        // New drives
        for (String root : current) {
            if (knownRoots.add(root)) {              // add() returns true if newly inserted
                LOG.info("New drive detected: {}", root);
                if (onDriveAdded != null) {
                    javafx.application.Platform.runLater(() -> onDriveAdded.accept(root));
                }
                fileIndexer.indexRoot(Path.of(root)); // incremental — does NOT clear existing
            }
        }

        // Removed drives
        Set<String> removed = new HashSet<>(knownRoots);
        removed.removeAll(current);
        for (String root : removed) {
            knownRoots.remove(root);
            LOG.info("Drive removed: {}", root);
            if (onDriveRemoved != null) {
                javafx.application.Platform.runLater(() -> onDriveRemoved.accept(root));
            }
            removeRootFromIndex(root);
        }
    }

    private void removeRootFromIndex(String rootPath) {
        // Snapshot to avoid modification during iteration
        List<String> toRemove = new ArrayList<>();
        for (var entry : fileIndex.getAll()) {
            if (entry.path().startsWith(rootPath)) toRemove.add(entry.path());
        }
        for (String path : toRemove) {
            fileIndex.remove(path);
            indexStore.delete(path);
        }
        LOG.info("Removed {} entries for drive {}", toRemove.size(), rootPath);
    }

    private static void addChildren(List<String> roots, String base) {
        File dir = new File(base);
        if (!dir.isDirectory()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            String abs = k.getAbsolutePath();
            if (k.isDirectory() && k.canRead() && !roots.contains(abs)) roots.add(abs);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }
}
