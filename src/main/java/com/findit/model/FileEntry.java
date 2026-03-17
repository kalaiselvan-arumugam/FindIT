package com.findit.model;

/**
 * Immutable record representing a single indexed file or directory entry.
 */
public record FileEntry(
    String name,         // filename only, e.g. "document.pdf"
    String path,         // full absolute path
    long size,           // bytes; -1 for directories
    long lastModified,   // epoch millis
    boolean isDirectory
) {
    /** Returns a human-readable size string, e.g. "12.4 MB", "340 KB". */
    public String formattedSize() {
        if (isDirectory) return "—";
        if (size < 0) return "—";
        if (size < 1_024) return size + " B";
        if (size < 1_048_576) return String.format("%.1f KB", size / 1_024.0);
        if (size < 1_073_741_824) return String.format("%.1f MB", size / 1_048_576.0);
        return String.format("%.2f GB", size / 1_073_741_824.0);
    }
}
