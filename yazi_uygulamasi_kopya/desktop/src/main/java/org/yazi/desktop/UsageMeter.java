package org.yazi.desktop;

import org.yazi.gateway.Usage;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Session totals of provider-reported token usage, for the status bar. FX-free.
 *
 * <p>Counts come from the provider (see {@link Usage}), never from estimates. A cost estimate is only shown when
 * the writer sets prices with {@code YAZI_PRICE_INPUT} and {@code YAZI_PRICE_OUTPUT} (USD per million tokens);
 * the app does not guess prices that change without notice. Thinking tokens are billed as output.</p>
 *
 * <p>In memory only; nothing about usage is ever sent to a model.</p>
 */
final class UsageMeter {

    /** Prices in USD per million tokens. */
    record Rates(double inputPerMillion, double outputPerMillion) {

        static Optional<Rates> fromEnvironment() {
            return from(System.getenv());
        }

        static Optional<Rates> from(Map<String, String> env) {
            Optional<Double> in = parse(env.get("YAZI_PRICE_INPUT"));
            Optional<Double> out = parse(env.get("YAZI_PRICE_OUTPUT"));
            return (in.isPresent() && out.isPresent()) ? Optional.of(new Rates(in.get(), out.get())) : Optional.empty();
        }

        private static Optional<Double> parse(String value) {
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            try {
                double d = Double.parseDouble(value.strip().replace(',', '.'));
                return (d >= 0 && Double.isFinite(d)) ? Optional.of(d) : Optional.empty();
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
    }

    private final Optional<Rates> rates;
    private int requests;
    private long prompt;
    private long output;
    private long thinking;
    private long cached;
    private long total;
    private Usage last;
    private int lastWindowChars;

    UsageMeter(Optional<Rates> rates) {
        this.rates = rates == null ? Optional.empty() : rates;
    }

    void record(Usage usage, int windowChars) {
        if (usage == null) {
            return;
        }
        requests++;
        prompt += usage.promptTokens();
        output += usage.outputTokens();
        thinking += usage.thinkingTokens();
        cached += usage.cachedTokens();
        // Some responses omit totalTokenCount; fall back to the parts.
        total += usage.totalTokens() > 0 ? usage.totalTokens()
                : (long) usage.promptTokens() + usage.outputTokens() + usage.thinkingTokens();
        last = usage;
        lastWindowChars = windowChars;
    }

    void reset() {
        requests = 0;
        prompt = output = thinking = cached = total = 0;
        last = null;
        lastWindowChars = 0;
    }

    int requests() {
        return requests;
    }

    long totalTokens() {
        return total;
    }

    Optional<Double> estimatedCost() {
        return rates.map(r -> (prompt * r.inputPerMillion() + (output + thinking) * r.outputPerMillion()) / 1e6);
    }

    /** Short status-bar text: {@code "No AI calls yet"} or {@code "12.4k tokens · 7 calls"} (+ cost if priced). */
    String chipText() {
        if (requests == 0) {
            return "No AI calls yet";
        }
        String base = compact(total) + " tokens · " + requests + (requests == 1 ? " call" : " calls");
        return estimatedCost().map(c -> base + " · ≈ " + money(c)).orElse(base);
    }

    /** The breakdown shown on hover. */
    String details() {
        StringBuilder out = new StringBuilder("This session (provider-reported)\n");
        out.append(String.format("  Calls           %,d%n", requests));
        out.append(String.format("  Input tokens    %,d%n", prompt));
        out.append(String.format("  Output tokens   %,d%n", output));
        if (thinking > 0) {
            out.append(String.format("  Thinking tokens %,d%n", thinking));
        }
        if (cached > 0) {
            out.append(String.format("  Cached tokens   %,d%n", cached));
        }
        out.append(String.format("  Total           %,d%n", total));
        if (requests > 0) {
            out.append(String.format("  Average / call  %,d%n", total / requests));
        }
        if (last != null) {
            out.append(String.format("%nLast call: %,d in / %,d out · context %,d chars%n",
                    last.promptTokens(), last.outputTokens(), lastWindowChars));
        }
        out.append('\n').append(estimatedCost()
                .map(c -> "Estimated cost " + money(c) + " (at your YAZI_PRICE_* rates)")
                .orElse("Set YAZI_PRICE_INPUT and YAZI_PRICE_OUTPUT (USD per 1M tokens)\nto see a cost estimate."));
        return out.toString();
    }

    /** 950, 12.4k, 1.25M. Locale-independent, since it is a compact code rather than prose. */
    static String compact(long n) {
        if (n < 1_000) {
            return Long.toString(n);
        }
        if (n < 1_000_000) {
            return trim(String.format(Locale.ROOT, n < 10_000 ? "%.2f" : n < 100_000 ? "%.1f" : "%.0f", n / 1e3)) + "k";
        }
        return trim(String.format(Locale.ROOT, "%.2f", n / 1e6)) + "M";
    }

    static String money(double usd) {
        return usd < 0.01 ? "<$0.01" : String.format(Locale.ROOT, "$%.2f", usd);
    }

    private static String trim(String decimal) {
        return decimal.contains(".") ? decimal.replaceAll("0+$", "").replaceAll("\\.$", "") : decimal;
    }
}
