package com.findit.engine;

import com.findit.model.FileEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe in-memory store of indexed {@link FileEntry} objects.
 * Backed by {@link CopyOnWriteArrayList} so the watcher thread can mutate
 * the list while search reads a stable snapshot.
 */
public class FileIndex {

    private final CopyOnWriteArrayList<FileEntry> entries = new CopyOnWriteArrayList<>();

    public void add(FileEntry entry) {
        entries.add(entry);
    }

    public void addAll(List<FileEntry> list) {
        entries.addAll(list);
    }

    public void setAll(List<FileEntry> list) {
        entries.clear();
        entries.addAll(list);
    }


    /** Remove the entry whose absolute path equals {@code path}. */
    public boolean remove(String path) {
        return entries.removeIf(e -> e.path().equals(path));
    }

    /** Replace an existing entry by path (used for modify events). */
    public void update(FileEntry updated) {
        remove(updated.path());
        entries.add(updated);
    }

    /** Returns a stable snapshot suitable for iteration / search. */
    public List<FileEntry> getAll() {
        return entries; // CopyOnWriteArrayList.iterator() already provides a snapshot
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    /** Collect all unique parent-directory paths for FileWatcher registration. */
    public List<String> getAllDirectoryPaths() {
        var set = new java.util.LinkedHashSet<String>();
        for (FileEntry e : entries) {
            java.io.File f = new java.io.File(e.path());
            java.io.File parent = e.isDirectory() ? f : f.getParentFile();
            if (parent != null) set.add(parent.getAbsolutePath());
        }
        return new ArrayList<>(set);
    }
}
