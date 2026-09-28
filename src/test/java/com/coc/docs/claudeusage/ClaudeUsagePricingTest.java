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
    @DisplayName("cache read is 0.10x the model's own input rate, a 5-minute cache write is 1.25x")
    void pricesCacheAtAMultipleOfInput() {
        // Opus 5 input is $5/M: cache read = $0.50/M, cache write = $6.25/M.
        BigDecimal cost = ClaudeUsagePricing.costFor("claude-opus-5", 0, 0, 1_000_000, 1_000_000);
        assertThat(cost).isEqualByComparingTo("6.75");
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
    @DisplayName("a 1-hour cache write is 2x input, a 5-minute one 1.25x")
    void oneHourCacheWriteIsTwiceInput() {
        // Opus 5.5 input $4/M: 1M at 1h = $8, 1M at 5m = $5.
        assertThat(ClaudeUsagePricing.costFor("claude-opus-5-5", 0, 0, 0, 2_000_000, 1_000_000))
                .isEqualByComparingTo("13");
        // A split larger than the writes themselves is clamped, never priced above them.
        assertThat(ClaudeUsagePricing.costFor("claude-opus-5-5", 0, 0, 0, 1_000_000, 5_000_000))
                .isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("reproduces Claude Code's usage page: Opus 5.5 $2.73, Haiku 4.5 $0.0020, total $2.74")
    void matchesClaudeCodeUsagePage() {
        // The figures Claude Code's own usage page showed (rounded there to 1.4k / 38.3k / 5.5m / 107.9k),
        // with Claude Code's cache writes at the 1-hour TTL.
        BigDecimal opus = ClaudeUsagePricing.costFor("claude-opus-5-5", 1_400, 38_300, 5_500_000, 107_900, 107_900);
        BigDecimal haiku = ClaudeUsagePricing.costFor("claude-haiku-4-5-20251001", 1_900, 16, 0, 0, 0);

        assertThat(opus.setScale(2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("2.73");
        assertThat(haiku.setScale(4, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("0.0020");
        assertThat(opus.add(haiku).setScale(2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("2.74");
    }
}
