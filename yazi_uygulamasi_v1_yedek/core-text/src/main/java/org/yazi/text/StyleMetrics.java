package org.yazi.text;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Local style measurement, ported from {@code LocalShadowCore} in writing_improve_v2. No dictionaries and no
 * model call; only sentence-length statistics and Unicode syntax detection.
 *
 * <p>Changes from the original: sentence splitting no longer breaks inside numbers ("3.5") or between a full
 * stop and its closing quote, and lowercase detection looks at sentence starts rather than line starts, so
 * writers who type {@code blood.he fell} are recognised.</p>
 */
public final class StyleMetrics {

    private StyleMetrics() {}

    /**
     * Splits after terminal punctuation even without a following space (some writers glue sentences), but not
     * inside a punctuation run, before a closing quote/bracket, or before a digit.
     */
    private static final Pattern SENTENCE_SPLIT = Pattern.compile(
            "(?<=[.!?…])(?![.!?…\"”»’)\\d])\\s*"
                    + "|(?<=[.!?…][\"”»’)])\\s*"
                    + "|\\n+");

    private static final Pattern WORD_SPLIT = Pattern.compile(
            "[\\s.,!?;:\\[\\]\"“”«»\\-–—…()]+");

    private static final Pattern INLINE_PARENTHETICAL = Pattern.compile("\\([^()\\n]{1,200}\\)");

    private static final double LOWERCASE_START_RATIO = 0.4;
    private static final double LINE_DELIMITED_RATIO = 0.7;

    public static StyleProfile measure(String rawText) {
        String text = (rawText == null) ? "" : rawText.strip();
        if (text.isEmpty()) {
            return StyleProfile.NEUTRAL;
        }
        List<Integer> lengths = sentences(text).stream()
                .map(StyleMetrics::wordCount)
                .filter(n -> n > 0)
                .toList();
        if (lengths.isEmpty()) {
            return StyleProfile.NEUTRAL;
        }

        double mean = lengths.stream().mapToInt(Integer::intValue).average().orElse(0.0);
        double variance = lengths.stream().mapToDouble(n -> (n - mean) * (n - mean)).average().orElse(0.0);

        return new StyleProfile(
                lengths.size(),
                round(mean, 1),
                round(Math.sqrt(variance), 2),
                INLINE_PARENTHETICAL.matcher(text).find(),
                startsMostlyLowercase(text),
                isLineDelimited(text),
                TextSignals.gluesAfterPunctuation(text));
    }

    static List<String> sentences(String text) {
        return Arrays.stream(SENTENCE_SPLIT.split(text))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static int wordCount(String sentence) {
        return (int) Arrays.stream(WORD_SPLIT.split(sentence)).filter(w -> !w.isBlank()).count();
    }

    private static boolean startsMostlyLowercase(String text) {
        int counted = 0;
        int lower = 0;
        for (String sentence : sentences(text)) {
            int cp = firstLetter(sentence);
            if (cp < 0) {
                continue;
            }
            counted++;
            if (Character.isLowerCase(cp)) {
                lower++;
            }
        }
        return counted >= 2 && lower >= counted * LOWERCASE_START_RATIO;
    }

    /** First code point of the sentence, skipping leading quotes and punctuation; -1 if it starts with a digit or has no letter. */
    private static int firstLetter(String sentence) {
        for (int i = 0; i < sentence.length(); ) {
            int cp = sentence.codePointAt(i);
            if (Character.isLetter(cp)) {
                return cp;
            }
            if (Character.isDigit(cp)) {
                return -1;
            }
            i += Character.charCount(cp);
        }
        return -1;
    }

    private static boolean isLineDelimited(String text) {
        int nonEmpty = 0;
        int formatted = 0;
        for (String line : text.split("\\R")) {
            String t = line.strip();
            if (t.isEmpty()) {
                continue;
            }
            nonEmpty++;
            boolean quoted = (t.startsWith("\"") && t.endsWith("\""))
                    || (t.startsWith("“") && t.endsWith("”"))
                    || (t.startsWith("«") && t.endsWith("»"));
            if (quoted || t.startsWith("-") || t.startsWith("*") || t.startsWith("—")) {
                formatted++;
            }
        }
        return nonEmpty >= 2 && formatted >= nonEmpty * LINE_DELIMITED_RATIO;
    }

    private static double round(double value, int decimals) {
        double factor = Math.pow(10.0, decimals);
        return Math.round(value * factor) / factor;
    }
}
