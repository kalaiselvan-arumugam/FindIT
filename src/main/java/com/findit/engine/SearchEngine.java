package com.findit.engine;

import com.findit.model.FileEntry;
import com.findit.util.SearchQuery;
import com.findit.util.WildcardMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Parses raw query strings and executes fast in-memory searches against
 * {@link FileIndex}. Search runs on a background thread; in-flight searches
 * are cancelled when a new query arrives.
 */
public class SearchEngine {

    private static final Logger LOG = LoggerFactory.getLogger(SearchEngine.class);
    private static final String REGEX_PREFIX = "re:";

    private final FileIndex fileIndex;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "findit-search");
        t.setDaemon(true);
        return t;
    });
    private Future<?> inFlight;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public SearchEngine(FileIndex fileIndex) { this.fileIndex = fileIndex; }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Submit an async search. The result is delivered via {@code onResult} on
     * the calling thread (wrap in Platform.runLater from the caller).
     */
    public void search(String rawQuery, boolean matchCase, boolean wholeWord,
                       boolean matchPath, boolean regexMode, int maxResults,
                       java.util.function.Consumer<List<FileEntry>> onResult) {
        // Cancel previous search
        if (inFlight != null && !inFlight.isDone()) {
            cancelled.set(true);
            inFlight.cancel(true);
        }
        cancelled.set(false);

        SearchQuery query = parseQuery(rawQuery, matchCase, wholeWord, matchPath, regexMode);
        inFlight = executor.submit(() -> {
            try {
                List<FileEntry> results = execute(query, maxResults);
                if (!cancelled.get()) onResult.accept(results);
            } catch (Exception e) {
                if (!cancelled.get()) LOG.warn("Search error: {}", e.getMessage());
            }
        });
    }

    // ── Query Parsing ─────────────────────────────────────────────────────────

    public SearchQuery parseQuery(String raw, boolean matchCase, boolean wholeWord,
                                  boolean matchPath, boolean regexMode) {
        if (raw == null || raw.isBlank())
            return new SearchQuery(List.of(), List.of(), matchCase, wholeWord, matchPath, regexMode);

        List<String> tokens = tokenize(raw);

        // Split tokens into OR groups (separated by "|" tokens)
        List<List<String>> orGroups = new ArrayList<>();
        List<String> currentGroup = new ArrayList<>();
        for (String tok : tokens) {
            if ("|".equals(tok)) {
                if (!currentGroup.isEmpty()) { orGroups.add(new ArrayList<>(currentGroup)); currentGroup.clear(); }
            } else {
                currentGroup.add(tok);
            }
        }
        if (!currentGroup.isEmpty()) orGroups.add(currentGroup);

        // Extract global excludes (start with '!')
        List<String> excludes = new ArrayList<>();
        for (List<String> group : orGroups) {
            group.removeIf(t -> { if (t.startsWith("!")) { excludes.add(t.substring(1)); return true; } return false; });
        }
        orGroups.removeIf(List::isEmpty);

        return new SearchQuery(orGroups, excludes, matchCase, wholeWord, matchPath, regexMode);
    }

    /** Tokenizes respecting "quoted strings" and treating '|' as a separator token. */
    private List<String> tokenize(String s) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') { inQuote = !inQuote; }
            else if (c == '|' && !inQuote) {
                if (!cur.isEmpty()) { tokens.add(cur.toString()); cur = new StringBuilder(); }
                tokens.add("|");
            } else if (c == ' ' && !inQuote) {
                if (!cur.isEmpty()) { tokens.add(cur.toString()); cur = new StringBuilder(); }
            } else { cur.append(c); }
        }
        if (!cur.isEmpty()) tokens.add(cur.toString());
        return tokens;
    }

    // ── Search Execution ──────────────────────────────────────────────────────

    private List<FileEntry> execute(SearchQuery query, int maxResults) {
        if (query.isEmpty()) return List.of();

        List<CompiledTerm> compiledExcludes = query.excludes().stream()
                .map(t -> new CompiledTerm(t, query)).toList();
        List<List<CompiledTerm>> compiledOrGroups = query.orGroups().stream()
                .map(g -> g.stream().map(t -> new CompiledTerm(t, query)).toList())
                .toList();

        List<FileEntry> all = fileIndex.getAll();
        List<FileEntry> results = new ArrayList<>(Math.min(1000, all.size() / 10 + 1));

        for (FileEntry entry : all) {
            if (Thread.currentThread().isInterrupted() || cancelled.get()) break;
            if (matches(entry, query, compiledExcludes, compiledOrGroups)) {
                results.add(entry);
                if (results.size() >= maxResults) break;
            }
        }
        return results;
    }

    private boolean matches(FileEntry entry, SearchQuery query, List<CompiledTerm> excludes, List<List<CompiledTerm>> orGroups) {
        String target = query.matchPath() ? entry.path() : entry.name();

        for (CompiledTerm ex : excludes) {
            if (ex.matches(target)) return false;
        }
        if (orGroups.isEmpty()) return true;

        for (List<CompiledTerm> group : orGroups) {
            if (groupMatches(group, target)) return true;
        }
        return false;
    }

    private boolean groupMatches(List<CompiledTerm> terms, String target) {
        for (CompiledTerm term : terms) {
            if (!term.matches(target)) return false;
        }
        return true;
    }

    private static class CompiledTerm {
        final Pattern pattern;
        final boolean wholeWord;
        final boolean matchCase;
        final String raw;

        CompiledTerm(String raw, SearchQuery context) {
            this.raw = raw;
            this.wholeWord = context.wholeWord();
            this.matchCase = context.matchCase();
            if (raw.startsWith(REGEX_PREFIX) || context.regexMode()) {
                String p = raw.startsWith(REGEX_PREFIX) ? raw.substring(REGEX_PREFIX.length()) : raw;
                int flags = context.matchCase() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                Pattern pc = null;
                try { pc = Pattern.compile(p, flags); } catch (PatternSyntaxException ignored) {}
                this.pattern = pc;
            } else if (WildcardMatcher.isWildcard(raw)) {
                this.pattern = WildcardMatcher.toPattern(raw, context.matchCase());
            } else {
                this.pattern = null;
            }
        }

        boolean matches(String target) {
            if (pattern != null) {
                return pattern.matcher(target).find();
            }
            if (matchCase) {
                if (wholeWord) return containsWholeWordExact(target, raw);
                return target.contains(raw);
            } else {
                if (wholeWord) return containsWholeWordIgnoreCase(target, raw);
                return containsIgnoreCase(target, raw);
            }
        }

        private static boolean containsIgnoreCase(String target, String term) {
            final int limit = target.length() - term.length();
            final int termLen = term.length();
            for (int i = 0; i <= limit; i++) {
                if (target.regionMatches(true, i, term, 0, termLen)) return true;
            }
            return false;
        }

        private static boolean containsWholeWordIgnoreCase(String target, String term) {
            final int limit = target.length() - term.length();
            final int termLen = term.length();
            for (int i = 0; i <= limit; i++) {
                if (target.regionMatches(true, i, term, 0, termLen)) {
                    boolean beforeOk = i == 0 || !Character.isLetterOrDigit(target.charAt(i - 1));
                    boolean afterOk  = i + termLen >= target.length() || !Character.isLetterOrDigit(target.charAt(i + termLen));
                    if (beforeOk && afterOk) return true;
                }
            }
            return false;
        }

        private static boolean containsWholeWordExact(String target, String term) {
            final int termLen = term.length();
            int idx = target.indexOf(term);
            while (idx >= 0) {
                boolean beforeOk = idx == 0 || !Character.isLetterOrDigit(target.charAt(idx - 1));
                boolean afterOk  = idx + termLen >= target.length() || !Character.isLetterOrDigit(target.charAt(idx + termLen));
                if (beforeOk && afterOk) return true;
                idx = target.indexOf(term, idx + 1);
            }
            return false;
        }
    }
}
