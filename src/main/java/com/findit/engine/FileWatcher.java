package com.findit.engine;

import com.findit.model.FileEntry;
import com.findit.persistence.IndexStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * Monitors the file system for create / delete / modify events using NIO
 * {@link WatchService} and applies incremental updates to {@link FileIndex}.
 *
 * <p>Events are batched for 200 ms before being applied to reduce thrash.
 *
 * <p>Runs on a single daemon thread that is alive for the entire app lifetime.
 */
public class FileWatcher {

    private static final Logger LOG = LoggerFactory.getLogger(FileWatcher.class);
    private static final long BATCH_MS = 200;

    private final FileIndex fileIndex;
    private final IndexStore indexStore;
    private FileIndexer fileIndexer; // for overflow re-index

    private WatchService watchService;
    private final Map<WatchKey, Path> watchedKeys = new ConcurrentHashMap<>();
    private Thread watchThread;
    private final List<Runnable> eventListeners = new CopyOnWriteArrayList<>();

    public FileWatcher(FileIndex fileIndex, IndexStore indexStore) {
        this.fileIndex   = fileIndex;
        this.indexStore  = indexStore;
    }

    /** Late-bind the indexer (needed to break circular constructor dependency). */
    public void setIndexer(FileIndexer indexer) {
        this.fileIndexer = indexer;
    }

    public void addListener(Runnable listener) { eventListeners.add(listener); }

    /** Start watching a set of directory paths (safe to call multiple times). */
    public void start(List<String> dirPaths) {
        stopInternal();
        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            LOG.error("Cannot create WatchService", e);
            return;
        }
        watchedKeys.clear();

        // Register directories on a separate thread to not block startup
        Thread regThread = new Thread(() -> {
            for (String dir : dirPaths) {
                registerPath(Path.of(dir));
            }
            LOG.info("FileWatcher: registered {} directories", watchedKeys.size());
        }, "findit-watcher-setup");
        regThread.setDaemon(true);
        regThread.start();

        watchThread = new Thread(this::watchLoop, "findit-watcher");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    /** Called after a re-index to update watched directories. */
    public void reload(List<String> dirPaths) {
        start(dirPaths);
    }

    private void stopInternal() {
        if (watchThread != null) watchThread.interrupt();
        if (watchService != null) {
            try { watchService.close(); } catch (IOException ignored) {}
        }
    }

    private void registerPath(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try {
            WatchKey key = dir.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
            watchedKeys.put(key, dir);
        } catch (IOException e) {
            LOG.debug("Cannot watch {}: {}", dir, e.getMessage());
        }
    }

    private void watchLoop() {
        LOG.info("FileWatcher loop started");
        while (!Thread.currentThread().isInterrupted()) {
            // Collect events for BATCH_MS before processing
            List<Runnable> batch = new ArrayList<>();
            long deadline = System.currentTimeMillis() + BATCH_MS;

            while (System.currentTimeMillis() < deadline) {
                WatchKey key;
                try {
                    long remaining = deadline - System.currentTimeMillis();
                    key = watchService.poll(Math.max(1, remaining), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ClosedWatchServiceException e) {
                    return;
                }
                if (key == null) break;

                Path dir = watchedKeys.get(key);
                if (dir == null) { key.reset(); continue; }

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    if (kind == OVERFLOW) {
                        LOG.warn("WatchService overflow — triggering full re-index");
                        fileIndexer.reindex();
                        key.reset();
                        return;
                    }

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    Path child = dir.resolve(ev.context());

                    if (kind == ENTRY_CREATE)      batch.add(() -> handleCreate(child));
                    else if (kind == ENTRY_DELETE) batch.add(() -> handleDelete(child));
                    else if (kind == ENTRY_MODIFY) batch.add(() -> handleModify(child));
                }
                key.reset();
            }

            // Apply batch
            if (!batch.isEmpty()) {
                batch.forEach(Runnable::run);
                eventListeners.forEach(Runnable::run);
            }
        }
    }

    private void handleCreate(Path path) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            FileEntry entry = new FileEntry(
                    path.getFileName().toString(),
                    path.toAbsolutePath().toString(),
                    attrs.isDirectory() ? -1L : attrs.size(),
                    attrs.lastModifiedTime().toMillis(),
                    attrs.isDirectory());
            fileIndex.add(entry);
            indexStore.upsert(entry);

            if (attrs.isDirectory()) {
                registerPath(path);
                // Index children
                try {
                    Files.walkFileTree(path, new SimpleFileVisitor<>() {
                        @Override public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                            registerPath(d);
                            FileEntry de = new FileEntry(d.getFileName() != null ? d.getFileName().toString() : d.toString(),
                                    d.toAbsolutePath().toString(), -1L, a.lastModifiedTime().toMillis(), true);
                            fileIndex.add(de); indexStore.upsert(de);
                            return FileVisitResult.CONTINUE;
                        }
                        @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                            FileEntry fe = new FileEntry(f.getFileName().toString(),
                                    f.toAbsolutePath().toString(), a.size(), a.lastModifiedTime().toMillis(), false);
                            fileIndex.add(fe); indexStore.upsert(fe);
                            return FileVisitResult.CONTINUE;
                        }
                        @Override public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; }
                    });
                } catch (IOException e) { LOG.debug("Cannot walk new dir {}: {}", path, e.getMessage()); }
            }
        } catch (IOException e) {
            LOG.debug("handleCreate error for {}: {}", path, e.getMessage());
        }
    }

    private void handleDelete(Path path) {
        String absPath = path.toAbsolutePath().toString();
        fileIndex.remove(absPath);
        indexStore.delete(absPath);
    }

    private void handleModify(Path path) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            FileEntry entry = new FileEntry(
                    path.getFileName().toString(),
                    path.toAbsolutePath().toString(),
                    attrs.isDirectory() ? -1L : attrs.size(),
                    attrs.lastModifiedTime().toMillis(),
                    attrs.isDirectory());
            fileIndex.update(entry);
            indexStore.upsert(entry);
        } catch (IOException e) {
            LOG.debug("handleModify error for {}: {}", path, e.getMessage());
        }
    }
}
