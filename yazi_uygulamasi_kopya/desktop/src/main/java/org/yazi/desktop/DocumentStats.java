package org.yazi.desktop;

/**
 * Word and character counts for the status bar. Pure and FX-free.
 *
 * <p>A word is a run of non-whitespace that contains at least one letter or digit, so "don't", "e-posta",
 * "Ankara'ya" and "3.5" count once, and a lone dash or quote does not count at all. Characters are Unicode code
 * points, so an emoji counts as one.</p>
 */
record DocumentStats(int words, int characters) {

    /** Reading speed used for the estimate, in words per minute. */
    static final int WORDS_PER_MINUTE = 230;

    static DocumentStats of(CharSequence text) {
        if (text == null || text.isEmpty()) {
            return new DocumentStats(0, 0);
        }
        int words = 0;
        int characters = 0;
        boolean inToken = false;
        boolean tokenHasWordChar = false;
        int i = 0;
        while (i < text.length()) {
            int cp = Character.codePointAt(text, i);
            i += Character.charCount(cp);
            characters++;
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                if (inToken && tokenHasWordChar) {
                    words++;
                }
                inToken = false;
                tokenHasWordChar = false;
            } else {
                inToken = true;
                tokenHasWordChar |= Character.isLetterOrDigit(cp);
            }
        }
        if (inToken && tokenHasWordChar) {
            words++;
        }
        return new DocumentStats(words, characters);
    }

    /** Whole minutes, rounded up; 0 only for an empty text. */
    int readingMinutes() {
        return (words + WORDS_PER_MINUTE - 1) / WORDS_PER_MINUTE;
    }

    /** For example {@code "1,234 words · 7,012 characters · 6 min read"}. */
    String describe() {
        String base = String.format("%,d %s · %,d %s", words, words == 1 ? "word" : "words",
                characters, characters == 1 ? "character" : "characters");
        return words == 0 ? base : base + " · " + readingMinutes() + " min read";
    }

    /** For a selection, e.g. {@code "42 of 1,234 words selected"}. */
    String describeSelection(DocumentStats document) {
        return String.format("%,d of %,d %s selected · %,d %s", words, document.words(),
                document.words() == 1 ? "word" : "words", characters, characters == 1 ? "character" : "characters");
    }
}
