package com.findit.engine;

import com.findit.model.FileEntry;
import com.findit.persistence.IndexStore;
import com.findit.util.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Walks the file system recursively using {@link Files#walkFileTree} and
 * populates {@link FileIndex}. All work runs on a background daemon thread.
 */
public class FileIndexer {

    private static final Logger LOG = LoggerFactory.getLogger(FileIndexer.class);
    private static final int PROGRESS_INTERVAL = 10_000;

    private final FileIndex fileIndex;
    private final IndexStore indexStore;
    private FileWatcher fileWatcher;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "findit-indexer");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean indexing = new AtomicBoolean(false);
    private Future<?> currentTask;

    /** Called every PROGRESS_INTERVAL files with the current count. */
    private Consumer<Long> progressListener;
    /** Called periodically with the current directory being scanned. */
    private Consumer<String> dirListener;
    /** Called when indexing finishes. */
    private Runnable doneListener;

    public FileIndexer(FileIndex fileIndex, IndexStore indexStore) {
        this.fileIndex   = fileIndex;
        this.indexStore  = indexStore;
        this.fileWatcher = null;
    }

    public void setFileWatcher(FileWatcher watcher) {
        this.fileWatcher = watcher;
    }

    public void onProgress(Consumer<Long> cb) { this.progressListener = cb; }

    /** Callback invoked periodically with the current directory being scanned. */
    public void onDirScanned(Consumer<String> cb) { this.dirListener = cb; }

    /** Callback invoked when indexing completes. */
    public void onDone(Runnable listener)          { this.doneListener = listener;     }
    public boolean isIndexing()                    { return indexing.get(); }

    /** Start a fresh index walk (cancels any in-progress walk first). */
    public void reindex() {
        if (currentTask != null) currentTask.cancel(true);
        currentTask = executor.submit(this::doIndex);
    }

    private void doIndex() {
        indexing.set(true);
        LOG.info("Starting full indexing...");

        List<FileEntry> newIndex = new ArrayList<>();
        AtomicLong count = new AtomicLong();
        List<String> excludePaths = parseExcludes();

        // Determine roots using DriveMonitor (auto-detects all drives/mounts)
        List<String> rootPaths = DriveMonitor.detectAllRoots();
        List<Path> roots = new ArrayList<>();
        for (String r : rootPaths) roots.add(Path.of(r));

        for (Path root : roots) {
            if (Thread.currentThread().isInterrupted()) break;
            try {
                long[] lastDirUpdate = {0L}; // Use array to mutate inside anonymous class
                Files.walkFileTree(root, new SimpleFileVisitor<>() {

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (Thread.currentThread().isInterrupted()) return FileVisitResult.TERMINATE;
                        String abs = dir.toAbsolutePath().toString();
                        for (String ex : excludePaths) {
                            if (abs.startsWith(ex)) return FileVisitResult.SKIP_SUBTREE;
                        }
                        newIndex.add(new FileEntry(
                                dir.getFileName() != null ? dir.getFileName().toString() : abs,
                                abs, -1L, attrs.lastModifiedTime().toMillis(), true));
                        long n = count.incrementAndGet();
                        if (n % PROGRESS_INTERVAL == 0) {
                            if (progressListener != null) progressListener.accept(n);
                        }
                        if (dirListener != null) {
                            long now = System.currentTimeMillis();
                            if (now - lastDirUpdate[0] > 100) {
                                dirListener.accept(abs);
                                lastDirUpdate[0] = now;
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (Thread.currentThread().isInterrupted()) return FileVisitResult.TERMINATE;
                        newIndex.add(new FileEntry(
                                file.getFileName().toString(),
                                file.toAbsolutePath().toString(),
                                attrs.size(),
                                attrs.lastModifiedTime().toMillis(),
                                false));
                        long n = count.incrementAndGet();
                        if (n % PROGRESS_INTERVAL == 0) {
                            if (progressListener != null) progressListener.accept(n);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE; // skip inaccessible
                    }
                });
            } catch (Exception e) {
                LOG.error("Error walking root {}: {}", root, e.getMessage(), e);
            }
        }

        LOG.info("Indexing complete: {} entries", newIndex.size());
        fileIndex.setAll(newIndex);
        indexing.set(false);

        // Persist and notify
        indexStore.save(newIndex);

        // (Re)start watcher now that directories are indexed
        if (Settings.get().watcherEnabled()) {
            fileWatcher.reload(fileIndex.getAllDirectoryPaths());
        }

        if (doneListener != null) doneListener.run();
    }

    /**
     * Incrementally index a single root (e.g. newly inserted USB drive).
     * Does NOT clear the existing index.
     */
    public void indexRoot(Path root) {
        executor.submit(() -> doIndexSingleRoot(root));
    }

    private void doIndexSingleRoot(Path root) {
        LOG.info("Incremental index of new root: {}", root);
        List<String> excludePaths = parseExcludes();
        AtomicLong count = new AtomicLong();
        long[] lastDirUpdate = {System.currentTimeMillis()};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (Thread.currentThread().isInterrupted()) return FileVisitResult.TERMINATE;
                    String abs = dir.toAbsolutePath().toString();
                    for (String ex : excludePaths) {
                        if (abs.startsWith(ex)) return FileVisitResult.SKIP_SUBTREE;
                    }
                    fileIndex.add(new FileEntry(
                            dir.getFileName() != null ? dir.getFileName().toString() : abs,
                            abs, -1L, attrs.lastModifiedTime().toMillis(), true));
                    long n = count.incrementAndGet();
                    if (dirListener != null) {
                        long now = System.currentTimeMillis();
                        if (now - lastDirUpdate[0] > 100) {
                            dirListener.accept(abs);
                            lastDirUpdate[0] = now;
                        }
                    }
                    if (n % PROGRESS_INTERVAL == 0 && progressListener != null) {
                        progressListener.accept(n);
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (Thread.currentThread().isInterrupted()) return FileVisitResult.TERMINATE;
                    fileIndex.add(new FileEntry(file.getFileName().toString(),
                            file.toAbsolutePath().toString(),
                            attrs.size(), attrs.lastModifiedTime().toMillis(), false));
                    count.incrementAndGet();
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.warn("Error walking new root {}: {}", root, e.getMessage());
        }
        LOG.info("Incremental index done: {} new entries from {}", count.get(), root);
        indexStore.save(fileIndex.getAll());
        if (fileWatcher != null && Settings.get().watcherEnabled()) {
            fileWatcher.reload(fileIndex.getAllDirectoryPaths());
        }
        if (doneListener != null) doneListener.run();
    }

    private List<String> parseExcludes() {
        String raw = Settings.get().excludePaths();
        List<String> list = new ArrayList<>();
        if (raw != null && !raw.isBlank()) {
            for (String p : raw.split(",")) {
                String t = p.trim();
                if (!t.isEmpty()) list.add(t);
            }
        }
        return list;
    }
}
