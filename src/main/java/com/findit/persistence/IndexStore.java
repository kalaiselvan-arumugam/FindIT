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

    public IndexStore() {
        new File(DB_DIR).mkdirs();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            for (String stmt : DDL.split(";")) {
                String sql = stmt.trim();
                if (!sql.isEmpty()) s.execute(sql);
            }
        } catch (SQLException e) {
            LOG.error("Failed to initialize SQLite schema", e);
        }
    }

    // ── Connection helper ─────────────────────────────────────────────────────

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(JDBC_URL);
    }

    // ── Load all rows ─────────────────────────────────────────────────────────

    public List<FileEntry> load() {
        List<FileEntry> list = new ArrayList<>();
        String sql = "SELECT path, name, size, modified, is_dir FROM file_index";
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(sql);
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

    public void save(List<FileEntry> entries) {
        String sql = "INSERT OR REPLACE INTO file_index(path,name,size,modified,is_dir) VALUES(?,?,?,?,?)";
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                // Clear table first
                try (Statement st = c.createStatement()) { st.execute("DELETE FROM file_index"); }
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
                c.commit();
                LOG.info("Saved {} entries to SQLite", entries.size());
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            }
        } catch (SQLException e) {
            LOG.error("Failed to save index to SQLite", e);
        }
    }

    // ── Incremental operations ────────────────────────────────────────────────

    public void upsert(FileEntry e) {
        String sql = "INSERT OR REPLACE INTO file_index(path,name,size,modified,is_dir) VALUES(?,?,?,?,?)";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
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

    public void delete(String path) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("DELETE FROM file_index WHERE path=?")) {
            ps.setString(1, path);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("delete failed for {}: {}", path, e.getMessage());
        }
    }

    public long count() {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM file_index")) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }
}
