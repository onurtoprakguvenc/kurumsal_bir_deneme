package org.yazi.desktop;

import org.yazi.model.Span;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain-text search for the find bar. Pure and FX-free.
 *
 * <p>Case-insensitive matching compares one {@code char} at a time ({@link String#regionMatches(boolean, int,
 * String, int, int)}), so a match always has the query's length. Lower-casing the whole text instead would break
 * offsets on Turkish input: {@code "İ".toLowerCase()} is two characters long. A side effect is that dotted and
 * dotless i match each other when case is ignored, which is usually what a Turkish writer searching for
 * "ilk" wants.</p>
 */
final class TextFinder {

    private TextFinder() {}

    /** Beyond this many matches the count is shown as "10,000+" and later ones are not navigated to. */
    static final int MAX_MATCHES = 10_000;

    /** All non-overlapping matches, left to right, at most {@link #MAX_MATCHES}. Empty for an empty query. */
    static List<Span> findAll(String text, String query, boolean matchCase) {
        List<Span> matches = new ArrayList<>();
        if (text == null || query == null || query.isEmpty() || query.length() > text.length()) {
            return matches;
        }
        int n = query.length();
        int last = text.length() - n;
        int i = 0;
        while (i <= last && matches.size() < MAX_MATCHES) {
            int at = matchCase ? text.indexOf(query, i) : indexOfIgnoreCase(text, query, i, last);
            if (at < 0) {
                break;
            }
            matches.add(new Span(at, at + n));
            i = at + n;
        }
        return matches;
    }

    /** Index of the first match starting at or after {@code offset}, wrapping to the first; -1 when none. */
    static int nextIndex(List<Span> matches, int offset) {
        if (matches.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < matches.size(); i++) {
            if (matches.get(i).start() >= offset) {
                return i;
            }
        }
        return 0;
    }

    /** Index of the last match starting before {@code offset}, wrapping to the last; -1 when none. */
    static int previousIndex(List<Span> matches, int offset) {
        if (matches.isEmpty()) {
            return -1;
        }
        for (int i = matches.size() - 1; i >= 0; i--) {
            if (matches.get(i).start() < offset) {
                return i;
            }
        }
        return matches.size() - 1;
    }

    /** Index of {@code span} among the matches, or -1. Used to show "3 of 12" for the current selection. */
    static int indexOf(List<Span> matches, Span span) {
        for (int i = 0; i < matches.size(); i++) {
            if (matches.get(i).equals(span)) {
                return i;
            }
        }
        return -1;
    }

    /** Text for the counter: "", "No matches", "3 of 12", "12 matches" or "10,000+ matches". */
    static String counter(List<Span> matches, int current, boolean queryEmpty) {
        if (queryEmpty) {
            return "";
        }
        if (matches.isEmpty()) {
            return "No matches";
        }
        String total = String.format("%,d", matches.size()) + (matches.size() >= MAX_MATCHES ? "+" : "");
        if (current < 0) {
            return total + (matches.size() == 1 ? " match" : " matches");
        }
        return String.format("%,d", current + 1) + " of " + total;
    }

    private static int indexOfIgnoreCase(String text, String query, int from, int last) {
        for (int i = from; i <= last; i++) {
            if (text.regionMatches(true, i, query, 0, query.length())) {
                return i;
            }
        }
        return -1;
    }
}
