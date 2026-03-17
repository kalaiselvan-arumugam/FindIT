package com.findit.util;

import java.util.List;

/**
 * Immutable parsed representation of a search query.
 * orGroups:   list of AND groups; a file matches if ANY group matches (OR semantics)
 * excludes:   terms that must NOT appear in the target string
 * flags:      case, whole-word, path, regex mode
 */
public record SearchQuery(
    List<List<String>> orGroups,   // outer = OR, inner = AND
    List<String> excludes,
    boolean matchCase,
    boolean wholeWord,
    boolean matchPath,
    boolean regexMode
) {
    /** True when the query is effectively empty (no search terms). */
    public boolean isEmpty() {
        return orGroups.isEmpty() && excludes.isEmpty();
    }
}
