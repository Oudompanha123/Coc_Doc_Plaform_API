package com.coc.docs.claudeusage;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClaudeUsagePricingTest {

    @Test
    @DisplayName("prices input and output at the model's own real rate")
    void pricesInputAndOutput() {
        // Sonnet 5: $2/M input, $10/M output.
        BigDecimal cost = ClaudeUsagePricing.costFor("claude-sonnet-5", 1_000_000, 1_000_000, 0, 0);
        assertThat(cost).isEqualByComparingTo("12");
    }

    @Test
    @DisplayName("cache read does not affect the Reference API Cost (ADR-066)")
    void cacheReadIsNotPriced() {
        BigDecimal without = ClaudeUsagePricing.costFor("claude-opus-5-5", 1_000, 2_000, 0, 0, 0);
        BigDecimal with = ClaudeUsagePricing.costFor("claude-opus-5-5", 1_000, 2_000, 50_000_000, 0, 0);
        assertThat(with).isEqualByComparingTo(without);
        assertThat(ClaudeUsagePricing.costFor("claude-opus-5", 0, 0, 1_000_000, 0)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("cache write, 5-minute or 1-hour, does not affect the Reference API Cost (ADR-066)")
    void cacheWriteIsNotPriced() {
        BigDecimal without = ClaudeUsagePricing.costFor("claude-opus-5-5", 1_000, 2_000, 0, 0, 0);
        assertThat(ClaudeUsagePricing.costFor("claude-opus-5-5", 1_000, 2_000, 0, 5_000_000, 0))
                .isEqualByComparingTo(without);
        assertThat(ClaudeUsagePricing.costFor("claude-opus-5-5", 1_000, 2_000, 0, 5_000_000, 5_000_000))
                .isEqualByComparingTo(without);
    }

    @Test
    @DisplayName("input and output each move the Reference API Cost")
    void inputAndOutputArePriced() {
        BigDecimal base = ClaudeUsagePricing.costFor("claude-opus-5-5", 1_000, 1_000, 0, 0, 0);
        // Opus 5.5: $4/M input, $20/M output.
        assertThat(ClaudeUsagePricing.costFor("claude-opus-5-5", 1_001_000, 1_000, 0, 0, 0).subtract(base))
                .isEqualByComparingTo("4");
        assertThat(ClaudeUsagePricing.costFor("claude-opus-5-5", 1_000, 1_001_000, 0, 0, 0).subtract(base))
                .isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("matches the longest known prefix, not the first one that fits")
    void matchesLongestPrefix() {
        // A dated/suffixed id must still resolve to the specific model, not a shorter accidental match.
        var opus5 = ClaudeUsagePricing.priceFor("claude-opus-5-20260115");
        assertThat(opus5.input()).isEqualByComparingTo("5");
        assertThat(opus5.output()).isEqualByComparingTo("25");
    }

    @Test
    @DisplayName("an unrecognised model prices as Opus, never understating cost")
    void unknownModelFallsBackToOpusRate() {
        var unknown = ClaudeUsagePricing.priceFor("some-future-model-nobody-has-priced-yet");
        assertThat(unknown.input()).isEqualByComparingTo("5");
        assertThat(unknown.output()).isEqualByComparingTo("25");
    }

    @Test
    @DisplayName("Haiku prices below Sonnet, which prices below Opus")
    void relativeOrderingHoldsAcrossTheFamily() {
        var haiku = ClaudeUsagePricing.priceFor("claude-haiku-4-5-20251001");
        var sonnet = ClaudeUsagePricing.priceFor("claude-sonnet-5");
        var opus = ClaudeUsagePricing.priceFor("claude-opus-5");

        assertThat(haiku.input()).isLessThan(sonnet.input());
        assertThat(sonnet.input()).isLessThan(opus.input());
        assertThat(haiku.output()).isLessThan(sonnet.output());
        assertThat(sonnet.output()).isLessThan(opus.output());
    }

    @Test
    @DisplayName("Opus 5.5 has its own $4/$20 rate, not Opus 5's by prefix, and reads cache at $0.20")
    void opus55IsNotPricedAsOpus5() {
        var opus55 = ClaudeUsagePricing.priceFor("claude-opus-5-5");
        assertThat(opus55.input()).isEqualByComparingTo("4");
        assertThat(opus55.output()).isEqualByComparingTo("20");
        assertThat(opus55.cacheRead()).isEqualByComparingTo("0.20");
        // Opus 5 itself (and its dated ids) still resolve to Opus 5.
        assertThat(ClaudeUsagePricing.priceFor("claude-opus-5-20260115").input()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("Fable 5.1 reads cache at $0.25 (0.025x), Fable 5 at the usual 0.10x")
    void fable51CacheReadRate() {
        assertThat(ClaudeUsagePricing.priceFor("claude-fable-5-1").cacheRead()).isEqualByComparingTo("0.25");
        assertThat(ClaudeUsagePricing.priceFor("claude-fable-5").cacheRead()).isEqualByComparingTo("1.0");
    }

    @Test
    @DisplayName("Input 246 / Output 124.9K / Cache Read 21.6M / Cache Write 243.4K is $4.70")
    void matchesTheDashboardExample() {
        // The dashboard totals, split across the two models they were run on.
        // Fable 5.1 ($10/$50): 100 in, 73,400 out = $3.671
        // Opus 5.5  ($4/$20):  146 in, 51,500 out = $1.030584
        // The cache columns (21.6M read, 243.4K written) add nothing.
        BigDecimal fable = ClaudeUsagePricing.costFor("claude-fable-5-1", 100, 73_400, 12_000_000, 143_400, 143_400);
        BigDecimal opus = ClaudeUsagePricing.costFor("claude-opus-5-5", 146, 51_500, 9_600_000, 100_000, 100_000);

        assertThat(fable.add(opus).setScale(2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("4.70");
    }
}
