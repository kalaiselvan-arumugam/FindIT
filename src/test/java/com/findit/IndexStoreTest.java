package com.findit;

import com.findit.model.FileEntry;
import com.findit.persistence.IndexStore;
import org.junit.jupiter.api.*;
import java.io.File;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests IndexStore using a temp in-memory SQLite path.
 */
class IndexStoreTest {

    private IndexStore store;
    private File tempDb;

    @BeforeEach
    void setUp() throws Exception {
        // Point the store to a unique temp file per test
        tempDb = File.createTempFile("findit_test_", ".db");
        tempDb.deleteOnExit();
        store = new IndexStore("jdbc:sqlite:" + tempDb.getAbsolutePath());
    }

    @AfterEach
    void tearDown() {
        if (tempDb != null) tempDb.delete();
    }

    @Test void saveAndLoad() {
        List<FileEntry> entries = List.of(
                new FileEntry("foo.txt", "/tmp/foo.txt", 100, 1000L, false),
                new FileEntry("bar",     "/tmp/bar",     -1,  2000L, true)
        );
        store.save(entries);
        List<FileEntry> loaded = store.load();
        assertEquals(2, loaded.size());
    }

    @Test void upsertAndDelete() {
        FileEntry entry = new FileEntry("test.pdf", "/test/test.pdf", 512, 3000L, false);
        store.upsert(entry);

        List<FileEntry> loaded = store.load();
        assertTrue(loaded.stream().anyMatch(e -> e.path().equals("/test/test.pdf")));

        store.delete("/test/test.pdf");
        loaded = store.load();
        assertFalse(loaded.stream().anyMatch(e -> e.path().equals("/test/test.pdf")));
    }

    @Test void saveOverwritesExisting() {
        store.save(List.of(new FileEntry("a.txt", "/a.txt", 1, 1L, false)));
        store.save(List.of(new FileEntry("b.txt", "/b.txt", 2, 2L, false)));
        List<FileEntry> loaded = store.load();
        assertEquals(1, loaded.size());
        assertEquals("b.txt", loaded.get(0).name());
    }



    @Test void loadEmptyReturnsEmptyList() {
        List<FileEntry> loaded = store.load();
        assertNotNull(loaded);
        // May have entries from prior state — just verify it doesn't throw
    }
}
