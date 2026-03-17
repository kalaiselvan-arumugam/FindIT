package com.findit;

import com.findit.engine.FileIndex;
import com.findit.engine.SearchEngine;
import com.findit.model.FileEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SearchEngineTest {

    private FileIndex index;
    private SearchEngine engine;

    @BeforeEach
    void setUp() {
        index = new FileIndex();
        engine = new SearchEngine(index);
        index.add(new FileEntry("report.pdf",    "/docs/report.pdf",    1024, System.currentTimeMillis(), false));
        index.add(new FileEntry("budget.xlsx",   "/docs/budget.xlsx",   2048, System.currentTimeMillis(), false));
        index.add(new FileEntry("temp_file.txt", "/tmp/temp_file.txt",  512,  System.currentTimeMillis(), false));
        index.add(new FileEntry("image.jpg",     "/photos/image.jpg",   8192, System.currentTimeMillis(), false));
        index.add(new FileEntry("logo.png",      "/photos/logo.png",    4096, System.currentTimeMillis(), false));
        index.add(new FileEntry("src",           "/projects/src",       -1,   System.currentTimeMillis(), true));
    }

    private List<FileEntry> searchSync(String q, boolean matchCase, boolean wholeWord, boolean matchPath, boolean regex) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<List<FileEntry>> result = new java.util.concurrent.atomic.AtomicReference<>();
        engine.search(q, matchCase, wholeWord, matchPath, regex, 100_000, found -> {
            result.set(found);
            latch.countDown();
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Search timed out");
        return result.get();
    }

    @Test void containsSearch() throws Exception {
        var r = searchSync("report", false, false, false, false);
        assertEquals(1, r.size());
        assertEquals("report.pdf", r.get(0).name());
    }

    @Test void andSearch() throws Exception {
        var r = searchSync("image jpg", false, false, false, false);
        assertEquals(1, r.size());
    }

    @Test void orSearch() throws Exception {
        var r = searchSync(".jpg | .png", false, false, false, false);
        assertEquals(2, r.size());
    }

    @Test void notSearch() throws Exception {
        var r = searchSync("!temp", false, false, false, false);
        assertFalse(r.stream().anyMatch(e -> e.name().contains("temp")));
    }

    @Test void wildcardSearch() throws Exception {
        var r = searchSync("*.pdf", false, false, false, false);
        assertEquals(1, r.size());
        assertEquals("report.pdf", r.get(0).name());
    }

    @Test void regexSearch() throws Exception {
        var r = searchSync("re:.*\\.xlsx$", false, false, false, false);
        assertEquals(1, r.size());
        assertEquals("budget.xlsx", r.get(0).name());
    }

    @Test void caseInsensitiveByDefault() throws Exception {
        var r = searchSync("REPORT", false, false, false, false);
        assertEquals(1, r.size());
    }

    @Test void caseSensitiveMiss() throws Exception {
        var r = searchSync("REPORT", true, false, false, false);
        assertEquals(0, r.size());
    }

    @Test void pathSearch() throws Exception {
        var r = searchSync("tmp", false, false, true, false);
        assertEquals(1, r.size());
    }

    @Test void emptyQueryReturnsEmpty() throws Exception {
        var r = searchSync("", false, false, false, false);
        assertTrue(r.isEmpty());
    }

    @Test void performanceMillion() throws Exception {
        FileIndex bigIndex = new FileIndex();
        for (int i = 0; i < 1_000_000; i++) {
            bigIndex.add(new FileEntry("file_" + i + ".txt", "/path/to/file_" + i + ".txt", i, System.currentTimeMillis(), false));
        }
        SearchEngine bigEngine = new SearchEngine(bigIndex);
        long start = System.currentTimeMillis();
        CountDownLatch latch = new CountDownLatch(1);
        bigEngine.search("file_500000", false, false, false, false, 100_000, r -> latch.countDown());
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 5000, "Search over 1M entries took " + elapsed + "ms (expected <5000ms)");
    }
}
