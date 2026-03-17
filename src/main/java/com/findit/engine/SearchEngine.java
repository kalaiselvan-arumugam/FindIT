package com.findit.engine;

import com.findit.model.FileEntry;
import com.findit.util.SearchQuery;
import com.findit.util.WildcardMatcher;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
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

    // Single-thread executor — searches are sequential, no need for a pool.
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "findit-search");
        t.setDaemon(true);
        return t;
    });

    private Future<?> inFlight;

    /**
     * Generation counter replaces AtomicBoolean cancelled.
     * Incrementing before each search makes stale callbacks identify themselves
     * and self-discard without a race window.
     */
    private final AtomicLong generation = new AtomicLong(0);

    public SearchEngine(FileIndex fileIndex) { this.fileIndex = fileIndex; }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Submit an async search.
     * Results are delivered via two Platform.runLater calls:
     *   1. A preview of the first 20 matches (instant feedback to the user).
     *   2. The complete result list once the full scan finishes.
     * If a newer search supersedes this one, neither callback fires.
     */
    public void search(String rawQuery, boolean matchCase, boolean wholeWord,
                       boolean matchPath, boolean regexMode, int maxResults,
                       BiConsumer<List<FileEntry>, Long> onResult) {

        if (inFlight != null && !inFlight.isDone()) inFlight.cancel(true);
        final long myGen = generation.incrementAndGet();

        SearchQuery query = parseQuery(rawQuery, matchCase, wholeWord, matchPath, regexMode);

        inFlight = executor.submit(() -> {
            long startTime = System.currentTimeMillis();
            try {
                List<CompiledTerm> compiledExcludes = query.excludes().stream()
                        .map(t -> new CompiledTerm(t, query)).toList();
                List<List<CompiledTerm>> compiledOrGroups = query.orGroups().stream()
                        .map(g -> g.stream().map(t -> new CompiledTerm(t, query)).toList())
                        .toList();

                if (query.isEmpty()) {
                    if (generation.get() == myGen) {
                        long duration = System.currentTimeMillis() - startTime;
                        Platform.runLater(() -> onResult.accept(List.of(), duration));
                    }
                    return;
                }

                // Take a plain-array snapshot once — avoids lock contention inside the stream
                FileEntry[] snapshot = fileIndex.snapshot();

                List<FileEntry> results = new ArrayList<>(Math.min(1000, snapshot.length / 100 + 1));
                boolean previewSent = false;

                for (FileEntry entry : snapshot) {
                    if (Thread.currentThread().isInterrupted() || generation.get() != myGen) break;
                    if (matches(entry, query, compiledExcludes, compiledOrGroups)) {
                        results.add(entry);
                        // Deliver first 20 results immediately so the UI is not blank
                        if (!previewSent && results.size() == 20) {
                            final List<FileEntry> preview = new ArrayList<>(results);
                            if (generation.get() == myGen) {
                                long duration = System.currentTimeMillis() - startTime;
                                Platform.runLater(() -> onResult.accept(preview, duration));
                            }
                            previewSent = true;
                        }
                        if (results.size() >= maxResults) break;
                    }
                }

                // Deliver full results
                if (generation.get() == myGen) {
                    final List<FileEntry> full = results;
                    long duration = System.currentTimeMillis() - startTime;
                    Platform.runLater(() -> onResult.accept(full, duration));
                }

            } catch (Exception e) {
                if (generation.get() == myGen) LOG.warn("Search error: {}", e.getMessage());
            }
        });
    }

    // ── Query Parsing (unchanged) ─────────────────────────────────────────────

    public SearchQuery parseQuery(String raw, boolean matchCase, boolean wholeWord,
                                  boolean matchPath, boolean regexMode) {
        if (raw == null || raw.isBlank())
            return new SearchQuery(List.of(), List.of(), matchCase, wholeWord, matchPath, regexMode);

        List<String> tokens = tokenize(raw);

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

        List<String> excludes = new ArrayList<>();
        for (List<String> group : orGroups) {
            group.removeIf(t -> { if (t.startsWith("!")) { excludes.add(t.substring(1)); return true; } return false; });
        }
        orGroups.removeIf(List::isEmpty);

        return new SearchQuery(orGroups, excludes, matchCase, wholeWord, matchPath, regexMode);
    }

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

    private boolean matches(FileEntry entry, SearchQuery query,
                            List<CompiledTerm> excludes,
                            List<List<CompiledTerm>> orGroups) {
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

    // ── CompiledTerm (unchanged except regexError field for future UI use) ────

    static class CompiledTerm {
        final Pattern pattern;
        final boolean isWildcard;
        final boolean wholeWord;
        final boolean matchCase;
        final String raw;
        /** Non-null when the user typed an invalid regex (re: prefix). */
        final String regexError;

        CompiledTerm(String raw, SearchQuery context) {
            this.raw = raw;
            this.wholeWord = context.wholeWord();
            this.matchCase = context.matchCase();
            Pattern pc = null;
            boolean wildcard = false;
            String err = null;
            if (raw.startsWith(REGEX_PREFIX) || context.regexMode()) {
                String p = raw.startsWith(REGEX_PREFIX) ? raw.substring(REGEX_PREFIX.length()) : raw;
                int flags = context.matchCase() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                try { pc = Pattern.compile(p, flags); }
                catch (PatternSyntaxException e) { err = e.getDescription(); }
            } else if (WildcardMatcher.isWildcard(raw)) {
                pc = WildcardMatcher.toPattern(raw, context.matchCase());
                wildcard = true;
            }
            this.pattern = pc;
            this.isWildcard = wildcard;
            this.regexError = err;
        }

        boolean matches(String target) {
            if (pattern != null) {
                // Wildcard patterns must match the whole string (shell glob semantics).
                // Regex (re:) patterns use find() so they can match substrings.
                return isWildcard ? pattern.matcher(target).matches()
                                  : pattern.matcher(target).find();
            }
            if (matchCase) {
                return wholeWord ? containsWholeWordExact(target, raw) : target.contains(raw);
            } else {
                return wholeWord ? containsWholeWordIgnoreCase(target, raw) : containsIgnoreCase(target, raw);
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
