package com.findit.util;

import java.util.regex.Pattern;

/**
 * Converts shell-style wildcard patterns (* and ?) to compiled {@link Pattern} objects.
 */
public final class WildcardMatcher {

    private WildcardMatcher() {}

    /**
     * Builds a compiled pattern from a wildcard string.
     * '*' matches zero or more characters; '?' matches exactly one character.
     * All other regex metacharacters are escaped so they are treated literally.
     */
    public static Pattern toPattern(String wildcard, boolean caseSensitive) {
        StringBuilder regex = new StringBuilder("^"); // anchor start
        for (int i = 0; i < wildcard.length(); i++) {
            char c = wildcard.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else if (c == '?') {
                regex.append('.');
            } else {
                if ("\\.[]{}()+^$|".indexOf(c) >= 0) {
                    regex.append('\\');
                }
                regex.append(c);
            }
        }
        regex.append("$"); // anchor end
        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        return Pattern.compile(regex.toString(), flags);
    }

    /**
     * Returns true if the subject matches the wildcard pattern.
     */
    public static boolean matches(String wildcard, String subject, boolean caseSensitive) {
        return toPattern(wildcard, caseSensitive).matcher(subject).matches();
    }

    /** Returns true if the string contains any wildcard character (* or ?). */
    public static boolean isWildcard(String s) {
        return s.contains("*") || s.contains("?");
    }
}
