package org.yazi.desktop;

import org.junit.jupiter.api.Test;
import org.yazi.gateway.Usage;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class UsageMeterTest {

    @Test
    void startsEmpty() {
        UsageMeter m = new UsageMeter(Optional.empty());
        assertEquals("No AI calls yet", m.chipText());
        assertEquals(0, m.requests());
        assertTrue(m.details().contains("Set YAZI_PRICE_INPUT"));
    }

    @Test
    void accumulatesProviderReportedTokens() {
        UsageMeter m = new UsageMeter(Optional.empty());
        m.record(new Usage(1_000, 200, 0, 0, 1_200), 4_000);
        m.record(new Usage(9_000, 1_500, 700, 300, 11_200), 16_000);
        assertEquals(2, m.requests());
        assertEquals(12_400, m.totalTokens());
        assertEquals("12.4k tokens · 2 calls", m.chipText());
        String d = m.details();
        assertTrue(d.contains("Thinking tokens"), d);
        assertTrue(d.contains("Cached tokens"), d);
        assertTrue(d.contains(String.format("context %,d chars", 16_000)), d);
    }

    @Test
    void missingTotalFallsBackToTheParts() {
        UsageMeter m = new UsageMeter(Optional.empty());
        m.record(new Usage(100, 20, 5, 0, 0), 10);
        assertEquals(125, m.totalTokens());
        m.record(null, 0);
        assertEquals(1, m.requests(), "null usage is ignored");
    }

    @Test
    void costIsOnlyEstimatedWithExplicitRatesAndThinkingIsBilledAsOutput() {
        UsageMeter m = new UsageMeter(Optional.of(new UsageMeter.Rates(0.30, 2.50)));
        m.record(new Usage(1_000_000, 100_000, 100_000, 0, 1_200_000), 0);
        // 1M in × 0.30 + (0.1M out + 0.1M thinking) × 2.50 = 0.30 + 0.50
        assertEquals(0.80, m.estimatedCost().orElseThrow(), 1e-9);
        assertEquals("1.2M tokens · 1 call · ≈ $0.80", m.chipText());
        assertTrue(m.details().contains("Estimated cost $0.80"));
    }

    @Test
    void resetClearsEverything() {
        UsageMeter m = new UsageMeter(Optional.empty());
        m.record(new Usage(10, 10, 0, 0, 20), 5);
        m.reset();
        assertEquals("No AI calls yet", m.chipText());
        assertEquals(0, m.totalTokens());
        assertFalse(m.details().contains("Last call"));
    }

    @Test
    void ratesComeFromTheEnvironmentOnlyWhenBothAreValid() {
        assertEquals(Optional.of(new UsageMeter.Rates(0.3, 2.5)),
                UsageMeter.Rates.from(Map.of("YAZI_PRICE_INPUT", "0.3", "YAZI_PRICE_OUTPUT", "2,5")));
        assertTrue(UsageMeter.Rates.from(Map.of("YAZI_PRICE_INPUT", "0.3")).isEmpty());
        assertTrue(UsageMeter.Rates.from(Map.of("YAZI_PRICE_INPUT", "x", "YAZI_PRICE_OUTPUT", "1")).isEmpty());
        assertTrue(UsageMeter.Rates.from(Map.of("YAZI_PRICE_INPUT", "-1", "YAZI_PRICE_OUTPUT", "1")).isEmpty());
        assertTrue(UsageMeter.Rates.from(Map.of("YAZI_PRICE_INPUT", "NaN", "YAZI_PRICE_OUTPUT", "1")).isEmpty());
        assertTrue(UsageMeter.Rates.from(Map.of()).isEmpty());
    }

    @Test
    void compactNumbersAndMoney() {
        assertEquals("0", UsageMeter.compact(0));
        assertEquals("950", UsageMeter.compact(950));
        assertEquals("1k", UsageMeter.compact(1_000));
        assertEquals("1.25k", UsageMeter.compact(1_250));
        assertEquals("12.4k", UsageMeter.compact(12_400));
        assertEquals("125k", UsageMeter.compact(125_000));
        assertEquals("1.25M", UsageMeter.compact(1_250_000));
        assertEquals("<$0.01", UsageMeter.money(0.004));
        assertEquals("$1.50", UsageMeter.money(1.5));
    }
}
