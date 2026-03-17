package com.findit.persistence;

import com.findit.model.FileEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite-backed persistence for the file index.
 * Database location: ~/.findit/index.db
 */
public class IndexStore {

    private static final Logger LOG = LoggerFactory.getLogger(IndexStore.class);
    private static final String DB_DIR  = System.getProperty("user.home") + File.separator + ".findit";
    private static final String DB_PATH = DB_DIR + File.separator + "index.db";
    private static final String JDBC_URL = "jdbc:sqlite:" + DB_PATH;

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS file_index (
                path     TEXT PRIMARY KEY,
                name     TEXT NOT NULL,
                size     INTEGER,
                modified INTEGER,
                is_dir   INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX IF NOT EXISTS idx_name ON file_index(name COLLATE NOCASE);
            """;

    /** Single shared connection — all methods synchronize on this. */
    private final Connection conn;

    public IndexStore() {
        new File(DB_DIR).mkdirs();
        Connection c = null;
        try {
            c = DriverManager.getConnection(JDBC_URL);
            try (Statement s = c.createStatement()) {
                // WAL mode: readers never block writers, writers never block readers
                s.execute("PRAGMA journal_mode=WAL");
                // NORMAL sync is safe with WAL and much faster than FULL
                s.execute("PRAGMA synchronous=NORMAL");
                // Larger cache = fewer disk reads on bulk load
                s.execute("PRAGMA cache_size=-65536"); // 64 MB
                for (String stmt : DDL.split(";")) {
                    String sql = stmt.trim();
                    if (!sql.isEmpty()) s.execute(sql);
                }
            }
        } catch (SQLException e) {
            LOG.error("Failed to initialize SQLite", e);
        }
        this.conn = c;
    }

    // ── Load all rows ─────────────────────────────────────────────────────────

    public synchronized List<FileEntry> load() {
        List<FileEntry> list = new ArrayList<>();
        String sql = "SELECT path, name, size, modified, is_dir FROM file_index";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new FileEntry(
                        rs.getString("name"),
                        rs.getString("path"),
                        rs.getLong("size"),
                        rs.getLong("modified"),
                        rs.getInt("is_dir") != 0));
            }
        } catch (SQLException e) {
            LOG.error("Failed to load index from SQLite (will re-index)", e);
        }
        return list;
    }

    // ── Bulk save ─────────────────────────────────────────────────────────────

    public synchronized void save(List<FileEntry> entries) {
        String sql = "INSERT OR REPLACE INTO file_index(path,name,size,modified,is_dir) VALUES(?,?,?,?,?)";
        try {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("DELETE FROM file_index");
            }
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int batch = 0;
                for (FileEntry e : entries) {
                    ps.setString(1, e.path());
                    ps.setString(2, e.name());
                    ps.setLong(3, e.size());
                    ps.setLong(4, e.lastModified());
                    ps.setInt(5, e.isDirectory() ? 1 : 0);
                    ps.addBatch();
                    if (++batch % 10_000 == 0) ps.executeBatch();
                }
                ps.executeBatch();
                conn.commit();
                LOG.info("Saved {} entries to SQLite", entries.size());
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            LOG.error("Failed to save index to SQLite", e);
        }
    }

    // ── Incremental operations ────────────────────────────────────────────────

    public synchronized void upsert(FileEntry e) {
        String sql = "INSERT OR REPLACE INTO file_index(path,name,size,modified,is_dir) VALUES(?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, e.path());
            ps.setString(2, e.name());
            ps.setLong(3, e.size());
            ps.setLong(4, e.lastModified());
            ps.setInt(5, e.isDirectory() ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException ex) {
            LOG.warn("upsert failed for {}: {}", e.path(), ex.getMessage());
        }
    }

    public synchronized void delete(String path) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM file_index WHERE path=?")) {
            ps.setString(1, path);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("delete failed for {}: {}", path, e.getMessage());
        }
    }

    /** Call on application exit to cleanly close the shared connection. */
    public synchronized void close() {
        try { if (conn != null && !conn.isClosed()) conn.close(); }
        catch (SQLException ignored) {}
    }
}
