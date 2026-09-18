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
    @DisplayName("cache read is 0.10x the model's own input rate, cache write is 1.25x")
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
}
