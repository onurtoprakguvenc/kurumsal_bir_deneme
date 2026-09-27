package org.example.llm;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Selectable Gemini models. Each profile carries the request limits used by {@link GeminiStreamEngine} and the
 * aliases accepted by the {@code model} CLI command, so {@code model 3.8} and {@code model gemini-3.8-flash}
 * select the same profile.
 *
 * <p>{@code contextCharBudget} caps how much retrieved source text one request may carry; the keyword index has
 * already narrowed the input to the best chunks, so the budget only protects latency and cost.</p>
 */
public enum ModelProfile {

    GEMINI_3_5_FLASH("gemini-3.5-flash", 8_192, 60_000, 0.2, "3.5", "3.5-flash"),
    GEMINI_3_6_FLASH("gemini-3.6-flash", 8_192, 60_000, 0.2, "3.6", "3.6-flash"),
    GEMINI_3_7_FLASH("gemini-3.7-flash", 8_192, 60_000, 0.2, "3.7", "3.7-flash"),
    GEMINI_3_8_FLASH("gemini-3.8-flash", 8_192, 60_000, 0.2, "3.8", "3.8-flash"),
    GEMINI_3_5_FLASH_LITE("gemini-3.5-flash-lite", 4_096, 30_000, 0.2, "3.5-lite", "3.5 flash lite", "lite");

    public static final ModelProfile DEFAULT = GEMINI_3_6_FLASH;

    private final String id;
    private final int maxOutputTokens;
    private final int contextCharBudget;
    private final double temperature;
    private final List<String> aliases;

    ModelProfile(String id, int maxOutputTokens, int contextCharBudget, double temperature, String... aliases) {
        this.id = id;
        this.maxOutputTokens = maxOutputTokens;
        this.contextCharBudget = contextCharBudget;
        this.temperature = temperature;
        this.aliases = List.of(aliases);
    }

    public String id() {
        return id;
    }

    public int maxOutputTokens() {
        return maxOutputTokens;
    }

    public int contextCharBudget() {
        return contextCharBudget;
    }

    public double temperature() {
        return temperature;
    }

    public List<String> aliases() {
        return aliases;
    }

    /** Accepts the model id, the constant name, or any alias; case and inner spacing are ignored. */
    public static Optional<ModelProfile> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String v = value.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        for (ModelProfile profile : values()) {
            if (profile.id.equals(v) || profile.name().toLowerCase(Locale.ROOT).equals(v)
                    || profile.aliases.contains(v)) {
                return Optional.of(profile);
            }
        }
        return Optional.empty();
    }

    /** Model ids with their short aliases, for help and error messages. */
    public static String ids() {
        return Arrays.stream(values())
                .map(p -> p.id + " (" + p.aliases.getFirst() + ")")
                .collect(Collectors.joining(", "));
    }
}
