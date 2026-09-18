package com.coc.docs.claudeusage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Real first-party Anthropic API pricing, for the "API-Equivalent Cost"
 * estimate (ADR-036, revision of ADR-035 decision 4).
 *
 * <p>The team is not billed these amounts — they are on subscription plans,
 * not pay-per-token API access. This answers "what would this usage have cost
 * on the API?", a size comparison for spotting who is doing the heavy lifting,
 * never actual spend. The dashboard says so wherever a figure appears.
 *
 * <p>Prices are USD per million tokens. Cache multipliers are relative to that
 * model's own input price: cache read = 0.10x, cache write = 1.25x (the
 * 5-minute kind, which is Claude Code's default — a 1-hour cache write prices
 * higher and is not distinguished here, same simplification the reference
 * table this was ported from makes).
 *
 * <p>Ported from the reference tool's {@code lib/usage/pricing.ts}. Values are
 * a snapshot; a real price change means editing this table, not a schema or
 * API change — {@code ClaudeUsageService.recordUsage} already prices at ingest
 * time server-side for exactly this reason.
 *
 * <p>// req: ADR-036
 */
public final class ClaudeUsagePricing {

    private ClaudeUsagePricing() {}

    public record ModelPrice(BigDecimal input, BigDecimal output) {}

    private static final BigDecimal CACHE_READ_MULTIPLIER = new BigDecimal("0.10");
    private static final BigDecimal CACHE_WRITE_MULTIPLIER = new BigDecimal("1.25");
    private static final BigDecimal ONE_MILLION = new BigDecimal(1_000_000);

    /** Enough decimal places that summing several models a day loses nothing worth keeping before the final round to the storage column's scale (4). */
    private static final int INTERMEDIATE_SCALE = 10;

    private static ModelPrice price(String input, String output) {
        return new ModelPrice(new BigDecimal(input), new BigDecimal(output));
    }

    /**
     * Matched longest-prefix-first, so {@code claude-opus-5} wins over a
     * shorter key that would also match, and a dated/suffixed id (e.g.
     * {@code claude-opus-4-5-20251101}) still resolves. Insertion order is
     * irrelevant to matching; longest key wins regardless of where it sits
     * in this table.
     */
    private static final Map<String, ModelPrice> PRICES = new LinkedHashMap<>();

    static {
        PRICES.put("claude-fable-5", price("10", "50"));
        PRICES.put("claude-mythos-5", price("10", "50"));
        PRICES.put("claude-opus-5", price("5", "25"));
        PRICES.put("claude-opus-4-8", price("5", "25"));
        PRICES.put("claude-opus-4-7", price("5", "25"));
        PRICES.put("claude-opus-4-6", price("5", "25"));
        PRICES.put("claude-sonnet-5", price("2", "10"));
        PRICES.put("claude-sonnet-4-6", price("3", "15"));
        PRICES.put("claude-haiku-4-5", price("1", "5"));
    }

    /** Priced as Opus when a model id matches nothing here, so cost is never understated. */
    private static final ModelPrice FALLBACK = price("5", "25");

    /** The model ids this table prices, for anything that wants to list them (e.g. a config UI). */
    public static List<String> knownModels() {
        return List.copyOf(PRICES.keySet());
    }

    public static ModelPrice priceFor(String model) {
        String id = model == null ? "" : model.toLowerCase(Locale.ROOT);
        String bestKey = null;
        ModelPrice best = null;
        for (Map.Entry<String, ModelPrice> entry : PRICES.entrySet()) {
            String key = entry.getKey();
            if (id.startsWith(key) && (bestKey == null || key.length() > bestKey.length())) {
                bestKey = key;
                best = entry.getValue();
            }
        }
        return best != null ? best : FALLBACK;
    }

    /**
     * Cost in USD for one model's token counts, at that model's own real rate.
     * Not rounded to the storage column's scale — {@link ClaudeUsageService}
     * sums this across every model in a day before rounding once, so summing
     * several models never compounds rounding error.
     */
    public static BigDecimal costFor(String model, long input, long output, long cacheRead, long cacheCreation) {
        ModelPrice p = priceFor(model);
        BigDecimal usd = BigDecimal.valueOf(input)
                .multiply(p.input())
                .add(BigDecimal.valueOf(output).multiply(p.output()))
                .add(BigDecimal.valueOf(cacheRead).multiply(p.input()).multiply(CACHE_READ_MULTIPLIER))
                .add(BigDecimal.valueOf(cacheCreation).multiply(p.input()).multiply(CACHE_WRITE_MULTIPLIER));
        return usd.divide(ONE_MILLION, INTERMEDIATE_SCALE, RoundingMode.HALF_UP);
    }
}
