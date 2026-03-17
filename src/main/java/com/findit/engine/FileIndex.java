package com.findit.engine;

import com.findit.model.FileEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe in-memory store of indexed {@link FileEntry} objects.
 * Backed by {@link CopyOnWriteArrayList} so the watcher thread can mutate
 * the list while search reads a stable snapshot.
 */
public class FileIndex {

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final List<FileEntry> entries = new ArrayList<>(1_500_000);

    public void add(FileEntry entry) {
        lock.writeLock().lock();
        try { entries.add(entry); }
        finally { lock.writeLock().unlock(); }
    }

    public void addAll(List<FileEntry> list) {
        lock.writeLock().lock();
        try { entries.addAll(list); }
        finally { lock.writeLock().unlock(); }
    }

    public void setAll(List<FileEntry> list) {
        lock.writeLock().lock();
        try { entries.clear(); entries.addAll(list); }
        finally { lock.writeLock().unlock(); }
    }

    /** Remove the entry whose absolute path equals {@code path}. */
    public boolean remove(String path) {
        lock.writeLock().lock();
        try { return entries.removeIf(e -> e.path().equals(path)); }
        finally { lock.writeLock().unlock(); }
    }

    /**
     * Atomic replace — entry never disappears from the index during the swap.
     * Previously used remove() + add() which had a visibility gap.
     */
    public void update(FileEntry updated) {
        lock.writeLock().lock();
        try {
            entries.replaceAll(e -> e.path().equals(updated.path()) ? updated : e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns a plain-array snapshot for use in parallel search streams.
     * Callers must NOT hold the read lock before calling this.
     */
    public FileEntry[] snapshot() {
        lock.readLock().lock();
        try { return entries.toArray(new FileEntry[0]); }
        finally { lock.readLock().unlock(); }
    }

    /** Returns a List copy. Prefer snapshot() for search; use getAll() for watcher setup. */
    public List<FileEntry> getAll() {
        lock.readLock().lock();
        try { return new ArrayList<>(entries); }
        finally { lock.readLock().unlock(); }
    }

    public void clear() {
        lock.writeLock().lock();
        try { entries.clear(); }
        finally { lock.writeLock().unlock(); }
    }

    public int size() {
        lock.readLock().lock();
        try { return entries.size(); }
        finally { lock.readLock().unlock(); }
    }

    /** Collect all unique parent-directory paths for FileWatcher registration. */
    public List<String> getAllDirectoryPaths() {
        lock.readLock().lock();
        try {
            var set = new java.util.LinkedHashSet<String>();
            for (FileEntry e : entries) {
                java.io.File f = new java.io.File(e.path());
                java.io.File parent = e.isDirectory() ? f : f.getParentFile();
                if (parent != null) set.add(parent.getAbsolutePath());
            }
            return new ArrayList<>(set);
        } finally {
            lock.readLock().unlock();
        }
    }
}
