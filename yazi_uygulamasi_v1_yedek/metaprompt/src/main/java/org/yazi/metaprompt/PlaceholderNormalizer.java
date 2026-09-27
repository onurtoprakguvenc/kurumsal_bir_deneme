package org.yazi.metaprompt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Stage 3 placeholder rule, enforced in Java instead of trusted to the model.
 *
 * <p>The rule as written in {@code STAGE3_SYSTEM}: strip all characters except {@code [a-zA-Z0-9_ ]}, replace
 * spaces and hyphens with underscores, collapse consecutive underscores, convert to UPPER_SNAKE_CASE, wrap in
 * double curly braces; every token inside {@code <dynamic_input>} must match {@code \{\{[A-Z0-9_]+\}\}}; an empty
 * or null input list yields a single {@code {{INPUT}}}.</p>
 *
 * <p>Where the prose and the examples disagree, the examples win: {@code "user-query" -> {{USER_QUERY}}} means
 * hyphens become underscores before other characters are stripped ({@code "schema.def" -> {{SCHEMADEF}}}).</p>
 */
public final class PlaceholderNormalizer {

    private PlaceholderNormalizer() {}

    public static final String FALLBACK_TOKEN = "{{INPUT}}";
    public static final Pattern VALID_TOKEN = Pattern.compile("\\{\\{[A-Z0-9_]+\\}\\}");

    private static final Pattern DYNAMIC_INPUT_BLOCK =
            Pattern.compile("(<dynamic_input>)(.*?)(</dynamic_input>)", Pattern.DOTALL);
    private static final Pattern ANY_TOKEN = Pattern.compile("\\{\\{[^{}]*\\}\\}");

    /** {@code "source text" -> "{{SOURCE_TEXT}}"}; empty when nothing usable remains. */
    public static String token(String inputName) {
        if (inputName == null) {
            return "";
        }
        String s = inputName.replaceAll("[\\s-]+", "_")
                .replaceAll("[^A-Za-z0-9_]", "")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "")
                .toUpperCase(Locale.ROOT);
        return s.isEmpty() ? "" : "{{" + s + "}}";
    }

    /** Input name to token, in input order, without duplicates; {@code {{INPUT}}} when nothing is usable. */
    public static Map<String, String> tokens(List<String> requiredInputs) {
        Map<String, String> out = new LinkedHashMap<>();
        if (requiredInputs != null) {
            for (String name : requiredInputs) {
                String t = token(name);
                if (!t.isEmpty() && !out.containsValue(t)) {
                    out.put(name.strip(), t);
                }
            }
        }
        if (out.isEmpty()) {
            out.put("input", FALLBACK_TOKEN);
        }
        return out;
    }

    /** The canonical {@code <dynamic_input>} body: one labelled line per required input. */
    public static String dynamicInputBody(List<String> requiredInputs) {
        StringBuilder body = new StringBuilder("\n");
        tokens(requiredInputs).forEach((name, token) -> body.append(name).append(": ").append(token).append('\n'));
        return body.toString();
    }

    public record Enforced(String manifest, boolean changed, List<String> invalidTokensFound) {}

    /**
     * Replaces the manifest's {@code <dynamic_input>} body with the canonical one (appending the block if the
     * model left it out). Reports whether anything changed and which non-conforming tokens the model had used.
     */
    public static Enforced enforce(String manifest, List<String> requiredInputs) {
        String text = (manifest == null) ? "" : manifest;
        String canonical = dynamicInputBody(requiredInputs);
        Matcher block = DYNAMIC_INPUT_BLOCK.matcher(text);
        if (!block.find()) {
            String appended = text.stripTrailing() + "\n<dynamic_input>" + canonical + "</dynamic_input>";
            return new Enforced(appended, true, List.of());
        }
        String body = block.group(2);
        List<String> invalid = new ArrayList<>();
        Matcher tokens = ANY_TOKEN.matcher(body);
        while (tokens.find()) {
            if (!VALID_TOKEN.matcher(tokens.group()).matches()) {
                invalid.add(tokens.group());
            }
        }
        if (body.equals(canonical)) {
            return new Enforced(text, false, invalid);
        }
        String rebuilt = text.substring(0, block.start(2)) + canonical + text.substring(block.end(2));
        return new Enforced(rebuilt, true, invalid);
    }
}
