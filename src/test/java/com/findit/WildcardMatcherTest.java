package com.findit;

import com.findit.util.WildcardMatcher;
import org.junit.jupiter.api.Test;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class WildcardMatcherTest {

    @Test void starMatchesEmpty() {
        assertMatches("*", "anything", false);
        assertMatches("*", "", false);
    }

    @Test void starMatchesPdf() {
        assertTrue(WildcardMatcher.toPattern("*.pdf", false).matcher("report.pdf").matches());
        assertFalse(WildcardMatcher.toPattern("*.pdf", false).matcher("report.txt").matches());
    }

    @Test void questionMark() {
        assertTrue(WildcardMatcher.toPattern("file?.txt", false).matcher("file1.txt").matches());
        assertFalse(WildcardMatcher.toPattern("file?.txt", false).matcher("file12.txt").matches());
    }

    @Test void caseInsensitive() {
        assertTrue(WildcardMatcher.toPattern("*.PDF", false).matcher("report.pdf").matches());
    }

    @Test void caseSensitive() {
        assertFalse(WildcardMatcher.toPattern("*.PDF", true).matcher("report.pdf").matches());
        assertTrue(WildcardMatcher.toPattern("*.PDF", true).matcher("report.PDF").matches());
    }

    @Test void isWildcardDetection() {
        assertTrue(WildcardMatcher.isWildcard("*.pdf"));
        assertTrue(WildcardMatcher.isWildcard("report?.txt"));
        assertFalse(WildcardMatcher.isWildcard("report.pdf"));
    }

    @Test void noWildcardBehavesAsLiteral() {
        assertTrue(WildcardMatcher.toPattern("report.pdf", false).matcher("report.pdf").matches());
    }

    private void assertMatches(String wildcard, String subject, boolean caseSensitive) {
        Pattern p = WildcardMatcher.toPattern(wildcard, caseSensitive);
        assertTrue(p.matcher(subject).matches(), wildcard + " should match " + subject);
    }
}
