package org.yazi.prose;

import org.yazi.model.Tier;

import java.util.Locale;
import java.util.Map;

/**
 * Which model serves which tier. Kept out of code paths so a model rename is a settings change.
 *
 * <p>Defaults are the names the old projects used. Override with the environment variables
 * {@code YAZI_MODEL_FAST}, {@code YAZI_MODEL_BALANCED} and {@code YAZI_MODEL_DEEP}.</p>
 */
public record ModelCatalog(String fast, String balanced, String deep) {

    public static final String DEFAULT_FLASH = "gemini-3.5-flash";
    public static final String DEFAULT_PRO = "gemini-3.1-pro";

    public static ModelCatalog defaults() {
        return new ModelCatalog(DEFAULT_FLASH, DEFAULT_FLASH, DEFAULT_PRO);
    }

    public static ModelCatalog fromEnvironment() {
        return from(System.getenv());
    }

    static ModelCatalog from(Map<String, String> env) {
        ModelCatalog d = defaults();
        return new ModelCatalog(
                pick(env, "YAZI_MODEL_FAST", d.fast()),
                pick(env, "YAZI_MODEL_BALANCED", d.balanced()),
                pick(env, "YAZI_MODEL_DEEP", d.deep()));
    }

    public String modelFor(Tier tier) {
        return switch (tier) {
            case FAST -> fast;
            case BALANCED -> balanced;
            case DEEP -> deep;
        };
    }

    /**
     * Flash-class models run with thinking off: in-place writing is not a reasoning task, and thinking tokens are
     * billed. Other models keep their default, since some of them cannot disable thinking.
     */
    public Integer thinkingBudgetFor(String model) {
        return model.toLowerCase(Locale.ROOT).contains("flash") ? 0 : null;
    }

    private static String pick(Map<String, String> env, String key, String fallback) {
        String value = env.get(key);
        return (value == null || value.isBlank()) ? fallback : value.strip();
    }
}
