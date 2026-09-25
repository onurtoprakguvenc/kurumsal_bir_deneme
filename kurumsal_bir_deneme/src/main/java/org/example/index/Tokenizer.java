package org.example.index;

import java.text.Normalizer;
import java.util.Set;

/**
 * Allocation-light Unicode tokenizer with accent/case folding tuned for Turkish and Western European text.
 *
 * <p>A token is a maximal run of letters and digits. Folding lower-cases, maps dotless/dotted I to {@code i}
 * and strips diacritics ({@code ş→s, ğ→g, ç→c, ö→o, ü→u, é→e}), so {@code "ŞİRKET"}, {@code "şirket"} and
 * {@code "sirket"} index identically. Positions count every token (stop words included) so phrase distances
 * remain exact even though stop words are not indexed.</p>
 */
public final class Tokenizer {

    public static final int MAX_TOKEN_CHARS = 48;

    /** Receives indexable tokens: folded term, token ordinal, and [start, end) char offsets in the input. */
    @FunctionalInterface
    public interface Sink {
        void token(String term, int position, int start, int end);
    }

    private static final char[] FOLD = new char[0x250];

    static {
        for (int c = 0; c < FOLD.length; c++) {
            FOLD[c] = computeFold((char) c);
        }
    }

    private static final Set<String> STOP_WORDS = Set.of(
            // English
            "an", "and", "are", "as", "at", "be", "by", "for", "from", "has", "have", "in", "is", "it", "its", "of",
            "on", "or", "that", "the", "this", "to", "was", "were", "will", "with",
            // Turkish (folded)
            "ve", "ile", "bir", "bu", "su", "da", "de", "ki", "mi", "mu", "icin", "gibi", "daha", "ya", "veya",
            "ise", "her", "cok", "ne", "olan", "olarak", "ancak", "ama", "hem", "yani");

    private static char computeFold(char c) {
        char lower = Character.toLowerCase(c);
        if (lower == '\u0131') { // dotless i
            return 'i';
        }
        String decomposed = Normalizer.normalize(String.valueOf(lower), Normalizer.Form.NFD);
        if (!decomposed.isEmpty() && Character.isLetter(decomposed.charAt(0)) && decomposed.charAt(0) < 0x250) {
            return decomposed.charAt(0);
        }
        return lower;
    }

    private static char fold(char c) {
        return c < FOLD.length ? FOLD[c] : Character.toLowerCase(c);
    }

    public boolean isStopWord(String foldedTerm) {
        return STOP_WORDS.contains(foldedTerm);
    }

    /**
     * Tokenizes {@code text}; returns the number of token positions consumed (including skipped tokens).
     */
    public int tokenize(CharSequence text, Sink sink) {
        StringBuilder term = new StringBuilder(MAX_TOKEN_CHARS + 8);
        int position = 0;
        int start = -1;
        int n = text.length();
        for (int i = 0; i <= n; i++) {
            char c = i < n ? text.charAt(i) : ' ';
            if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(text.charAt(i + 1))) {
                int cp = Character.toCodePoint(c, text.charAt(i + 1));
                if (Character.isLetterOrDigit(cp)) {
                    if (start < 0) {
                        start = i;
                    }
                    if (term.length() <= MAX_TOKEN_CHARS) {
                        term.appendCodePoint(Character.toLowerCase(cp));
                    }
                    i++;
                    continue;
                }
            }
            if (isWordChar(c)) {
                if (start < 0) {
                    start = i;
                }
                int type = Character.getType(c);
                if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                        || type == Character.COMBINING_SPACING_MARK) {
                    continue;
                }
                if (term.length() <= MAX_TOKEN_CHARS) {
                    term.append(fold(c));
                }
            } else if (start >= 0) {
                if (emit(term, position, start, i, sink)) {
                    position++;
                }
                term.setLength(0);
                start = -1;
            }
        }
        return position;
    }

    private boolean emit(StringBuilder term, int position, int start, int end, Sink sink) {
        int len = term.length();
        if (len == 0) {
            return false;
        }
        if (len > MAX_TOKEN_CHARS) {
            return true;
        }
        String value = term.toString();
        if ((len >= 2 || Character.isDigit(value.charAt(0))) && !STOP_WORDS.contains(value)) {
            sink.token(value, position, start, end);
        }
        return true;
    }

    private static boolean isWordChar(char c) {
        if (Character.isLetterOrDigit(c)) {
            return true;
        }
        int type = Character.getType(c);
        return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK;
    }

    /** Folds a single user-supplied term exactly like indexed text; returns {@code null} if nothing remains. */
    public String normalizeTerm(String raw) {
        String[] out = {null};
        tokenize(raw, (term, position, start, end) -> {
            if (out[0] == null) {
                out[0] = term;
            }
        });
        return out[0];
    }
}
