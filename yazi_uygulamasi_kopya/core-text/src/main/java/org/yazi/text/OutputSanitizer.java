package org.yazi.text;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Removes wrapper artefacts a model sometimes puts around the text it was asked for: code fences, echoed prompt
 * cues ({@code CONTINUATION:}), and leaked reasoning lines.
 *
 * <p>Only removes material at the very start (and a fence at the very end), and only when it is unmistakably
 * meta-language. Prose is never touched: {@code "Wait," she said} and {@code Thinking of her, he ran} survive.
 * The previous {@code StreamSanitizer} stripped any leading "Wait"/"Thinking" word and so ate the first word
 * of legitimate continuations.</p>
 *
 * <p>Leading line breaks are kept (the model may be opening a new paragraph); trailing whitespace is dropped.</p>
 */
public final class OutputSanitizer {

    private OutputSanitizer() {}

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern OPENING_FENCE = Pattern.compile("\\A\\s*```[\\w-]*[ \\t]*\\R?");
    private static final Pattern CLOSING_FENCE = Pattern.compile("\\R?[ \\t]*```\\s*\\z");

    /** The cue words our own prompts end with; matched case-sensitively so ordinary prose is safe. */
    private static final Pattern ECHOED_CUE = Pattern.compile(
            "\\A[ \\t]*(?:CONTINUATION|REPLACEMENT|ANALYSIS|OUTPUT)[ \\t]*:[ \\t]*\\R?");

    /** A whole first line that talks about the task instead of doing it. */
    private static final Pattern REASONING_LINE = Pattern.compile(
            "\\A[ \\t]*[(\\[]?[ \\t]*(?:let me think|thinking:|the directive (?:is|asks|says|wants)|if i append"
                    + "|the user (?:wants|asked|is asking))[^\\n]*(?:\\R+|\\z)", FLAGS);

    /** "(Wait, if I append ...)" style asides: a bracketed first line that mentions the task itself. */
    private static final Pattern BRACKETED_ASIDE = Pattern.compile(
            "\\A[ \\t]*[(\\[](?=[^\\n]*\\b(?:directive|append|instruction|prompt|continuation|the user)\\b)"
                    + "[^\\n]*[)\\]][ \\t]*(?:\\R+|\\z)", FLAGS);

    private static final List<Pattern> LEADING_ARTEFACTS = List.of(ECHOED_CUE, REASONING_LINE, BRACKETED_ASIDE);
    private static final int MAX_PASSES = 5;

    public static String clean(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String text = raw;
        if (text.stripLeading().startsWith("```")) {
            text = OPENING_FENCE.matcher(text).replaceFirst("");
            text = CLOSING_FENCE.matcher(text).replaceFirst("");
        }
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            String before = text;
            for (Pattern artefact : LEADING_ARTEFACTS) {
                text = artefact.matcher(text).replaceFirst("");
            }
            if (text.equals(before)) {
                break;
            }
        }
        return text.stripTrailing();
    }
}
