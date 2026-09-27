package org.yazi.metaprompt;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The persona ban from the Stage 2 and Stage 3 prompts, enforced in Java: sentences that open with identity
 * framing ("Act as...", "You are a...", "Assume the role of...") are removed from the prompt text the model
 * produced. Your note on prompt_gelistirme ("hala 'act as a' diyen kısım var") is why this is no longer left to
 * the model.
 *
 * <p>Only sentence openings are matched, so "If you are a new user..." or a quoted mention such as
 * {@code Never use "Act as" framing.} is left alone.</p>
 */
public final class PersonaLinter {

    private PersonaLinter() {}

    /** A sentence that was removed, and from which field. */
    public record Finding(String field, String removed) {}

    public record Result(String text, List<Finding> findings) {}

    /**
     * Sentence start (text start, line start with an optional list/heading marker, or after terminal
     * punctuation), then a persona phrase, then everything up to the end of that sentence or line.
     */
    private static final Pattern PERSONA_SENTENCE = Pattern.compile(
            "(?im)(?:^[ \\t]*(?:[-*#>]+[ \\t]*|\\d+[.)][ \\t]*)?|(?<=[.!?])[ \\t]+)"
                    + "(?:act\\s+as|you\\s+are\\s+an?|you're\\s+an?|assume\\s+the\\s+role\\s+of"
                    + "|take\\s+on\\s+the\\s+role\\s+of|adopt\\s+the\\s+persona\\s+of)\\b"
                    + "[^\\n]*?(?:[.!?](?=\\s|$)|(?=\\n)|$)");

    public static Result strip(String field, String text) {
        if (text == null || text.isEmpty()) {
            return new Result(text == null ? "" : text, List.of());
        }
        List<Finding> findings = new ArrayList<>();
        Matcher m = PERSONA_SENTENCE.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int copied = 0;
        while (m.find()) {
            if (m.group().isBlank()) {
                continue;
            }
            findings.add(new Finding(field, m.group().strip()));
            out.append(text, copied, m.start());
            int end = m.end();
            boolean atLineStart = m.start() == 0 || text.charAt(m.start() - 1) == '\n';
            if (atLineStart) {
                // "You are X. Do Y." -> "Do Y." ; a line that held only the persona sentence disappears.
                while (end < text.length() && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) {
                    end++;
                }
                if (end < text.length() && text.charAt(end) == '\r') {
                    end++;
                }
                if (end < text.length() && text.charAt(end) == '\n'
                        && (m.start() == 0 || isLineEmpty(out))) {
                    end++;
                }
            }
            copied = end;
        }
        if (findings.isEmpty()) {
            return new Result(text, List.of());
        }
        out.append(text, copied, text.length());
        return new Result(out.toString().strip(), List.copyOf(findings));
    }

    /** True when the text built so far ends at the start of a line (nothing written on the current line). */
    private static boolean isLineEmpty(StringBuilder out) {
        return out.isEmpty() || out.charAt(out.length() - 1) == '\n';
    }

    /** True if {@code text} still opens a sentence with persona framing. */
    public static boolean hasPersona(String text) {
        return text != null && PERSONA_SENTENCE.matcher(text).find();
    }
}
