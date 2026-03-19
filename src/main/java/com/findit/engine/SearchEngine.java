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
                        runSafe(() -> onResult.accept(List.of(), duration));
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
                                runSafe(() -> onResult.accept(preview, duration));
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
                    runSafe(() -> onResult.accept(full, duration));
                }

            } catch (Exception e) {
                if (generation.get() == myGen) LOG.warn("Search error: {}", e.getMessage());
            }
        });
    }

    private void runSafe(Runnable r) {
        try {
            Platform.runLater(r);
        } catch (IllegalStateException e) {
            r.run(); // Fallback for headless tests
        }
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
        boolean globalMatchPath = query.matchPath();

        for (CompiledTerm ex : excludes) {
            if (ex.matches(entry, globalMatchPath)) return false;
        }
        if (orGroups.isEmpty()) return true;

        for (List<CompiledTerm> group : orGroups) {
            if (groupMatches(group, entry, globalMatchPath)) return true;
        }
        return false;
    }

    private boolean groupMatches(List<CompiledTerm> terms, FileEntry entry, boolean globalMatchPath) {
        for (CompiledTerm term : terms) {
            if (!term.matches(entry, globalMatchPath)) return false;
        }
        return true;
    }

    // ── CompiledTerm (unchanged except regexError field for future UI use) ────

    static class CompiledTerm {
        final Pattern pattern;
        final boolean isWildcard;
        final List<Pattern> multiWildcards;
        final boolean wholeWord;
        final boolean matchCase;
        final String raw;
        final String regexError;

        final boolean isSizeFilter;
        final long sizeThreshold;
        final boolean sizeGreater;
        final boolean forcePathMatch;
        final List<String> multiPaths;

        CompiledTerm(String originalRaw, SearchQuery context) {
            String workingRaw = originalRaw;
            this.wholeWord = context.wholeWord();
            this.matchCase = context.matchCase();

            boolean sizeF = false;
            long sThresh = 0;
            boolean sGreater = true;
            boolean fPath = false;
            List<String> mPaths = null;

            if (workingRaw.toLowerCase().startsWith("size:")) {
                sizeF = true;
                String s = workingRaw.substring(5).toLowerCase().trim();
                if (s.startsWith("<")) { sGreater = false; s = s.substring(1).trim(); }
                else if (s.startsWith(">")) { sGreater = true; s = s.substring(1).trim(); }
                long mult = 1;
                if (s.endsWith("gb") || s.endsWith("g")) { mult = 1024L*1024*1024; s = s.replaceAll("[a-z]", ""); }
                else if (s.endsWith("mb") || s.endsWith("m")) { mult = 1024L*1024; s = s.replaceAll("[a-z]", ""); }
                else if (s.endsWith("kb") || s.endsWith("k")) { mult = 1024L; s = s.replaceAll("[a-z]", ""); }
                else { s = s.replaceAll("[a-z]", ""); }
                try { sThresh = (long) (Double.parseDouble(s) * mult); } catch (Exception ignored) {}
            } else if (workingRaw.toLowerCase().startsWith("path:")) {
                fPath = true;
                workingRaw = workingRaw.substring(5);
                if (workingRaw.contains(",")) {
                    mPaths = new ArrayList<>();
                    for (String part : workingRaw.split(",")) {
                        String trimmed = part.trim();
                        if (!trimmed.isEmpty()) mPaths.add(trimmed);
                    }
                }
            }

            this.isSizeFilter = sizeF;
            this.sizeThreshold = sThresh;
            this.sizeGreater = sGreater;
            this.forcePathMatch = fPath;
            this.multiPaths = mPaths;
            this.raw = workingRaw;

            Pattern pc = null;
            boolean wildcard = false;
            List<Pattern> mWildcards = null;
            String err = null;

            if (!isSizeFilter) {
                if (workingRaw.startsWith(REGEX_PREFIX) || context.regexMode()) {
                    String p = workingRaw.startsWith(REGEX_PREFIX) ? workingRaw.substring(REGEX_PREFIX.length()) : workingRaw;
                    int flags = context.matchCase() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                    try { pc = Pattern.compile(p, flags); }
                    catch (PatternSyntaxException e) { err = e.getDescription(); }
                } else if (workingRaw.toLowerCase().startsWith("ext:")) {
                    String extStr = workingRaw.substring(4);
                    if (extStr.contains(",")) {
                        mWildcards = new ArrayList<>();
                        for (String ext : extStr.split(",")) {
                            ext = ext.trim();
                            if (!ext.isEmpty()) {
                                mWildcards.add(WildcardMatcher.toPattern("*." + (ext.startsWith(".") ? ext.substring(1) : ext), context.matchCase()));
                            }
                        }
                    } else {
                        String ext = extStr.trim();
                        pc = WildcardMatcher.toPattern("*." + (ext.startsWith(".") ? ext.substring(1) : ext), context.matchCase());
                        wildcard = true;
                    }
                } else if (WildcardMatcher.isWildcard(workingRaw)) {
                    pc = WildcardMatcher.toPattern(workingRaw, context.matchCase());
                    wildcard = true;
                }
            }

            this.pattern = pc;
            this.isWildcard = wildcard;
            this.multiWildcards = mWildcards;
            this.regexError = err;
        }

        boolean matches(FileEntry entry, boolean globalMatchPath) {
            if (isSizeFilter) {
                if (entry.isDirectory()) return false;
                return sizeGreater ? entry.size() >= sizeThreshold : entry.size() <= sizeThreshold;
            }

            String target = (globalMatchPath || forcePathMatch) ? entry.path() : entry.name();

            if (forcePathMatch && multiPaths != null) {
                for (String p : multiPaths) {
                    boolean pMatches = matchCase ? (wholeWord ? containsWholeWordExact(target, p) : target.contains(p)) 
                                                 : (wholeWord ? containsWholeWordIgnoreCase(target, p) : containsIgnoreCase(target, p));
                    if (pMatches) return true;
                }
                return false;
            }

            if (multiWildcards != null) {
                for (Pattern p : multiWildcards) {
                    if (p.matcher(target).matches()) return true;
                }
                return false;
            }

            if (pattern != null) {
                return isWildcard ? pattern.matcher(target).matches() : pattern.matcher(target).find();
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
