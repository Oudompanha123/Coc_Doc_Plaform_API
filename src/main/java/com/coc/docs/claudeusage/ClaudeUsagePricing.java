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
 * <p>Prices are USD per million tokens. Cache reads have their own per-model
 * rate — 0.10x input on most models, but 0.05x on Opus 5.5 and 0.025x on
 * Fable/Mythos 5.1, so it is a column of the table rather than a multiplier
 * (ADR-054). Cache writes are a multiple of that model's input price: 1.25x
 * for the 5-minute TTL, 2x for the 1-hour TTL. Claude Code writes almost all
 * of its cache at 1 hour, which is why the reporter sends that split
 * ({@code cache_creation.ephemeral_1h_input_tokens}) rather than letting it
 * all price as 5-minute and understate (ADR-054, revising ADR-036).
 *
 * <p>Ported from the reference tool's {@code lib/usage/pricing.ts}. Values are
 * a snapshot; a real price change means editing this table, not a schema or
 * API change — {@code ClaudeUsageService.recordUsage} already prices at ingest
 * time server-side for exactly this reason.
 *
 * <p>// req: ADR-036, ADR-054
 */
public final class ClaudeUsagePricing {

    private ClaudeUsagePricing() {}

    public record ModelPrice(BigDecimal input, BigDecimal output, BigDecimal cacheRead) {}

    /** Cache read on every model not listed with its own rate. */
    private static final BigDecimal DEFAULT_CACHE_READ_MULTIPLIER = new BigDecimal("0.10");
    private static final BigDecimal CACHE_WRITE_5M_MULTIPLIER = new BigDecimal("1.25");
    private static final BigDecimal CACHE_WRITE_1H_MULTIPLIER = new BigDecimal("2");
    private static final BigDecimal ONE_MILLION = new BigDecimal(1_000_000);

    /** Enough decimal places that summing several models a day loses nothing worth keeping before the final round to the storage column's scale (4). */
    private static final int INTERMEDIATE_SCALE = 10;

    private static ModelPrice price(String input, String output) {
        BigDecimal in = new BigDecimal(input);
        return new ModelPrice(in, new BigDecimal(output), in.multiply(DEFAULT_CACHE_READ_MULTIPLIER));
    }

    private static ModelPrice price(String input, String output, String cacheRead) {
        return new ModelPrice(new BigDecimal(input), new BigDecimal(output), new BigDecimal(cacheRead));
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
        PRICES.put("claude-fable-5-1", price("10", "50", "0.25"));
        PRICES.put("claude-mythos-5-1", price("10", "50", "0.25"));
        PRICES.put("claude-fable-5", price("10", "50"));
        PRICES.put("claude-mythos-5", price("10", "50"));
        // Without its own key, "claude-opus-5-5" prefix-matched "claude-opus-5" and priced at $5/$25.
        PRICES.put("claude-opus-5-5", price("4", "20", "0.20"));
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
     *
     * <p>Every cache write priced as 5-minute — what a reporter that predates
     * the 1-hour split (ADR-054) is taken to mean.
     */
    public static BigDecimal costFor(String model, long input, long output, long cacheRead, long cacheCreation) {
        return costFor(model, input, output, cacheRead, cacheCreation, 0);
    }

    /**
     * As above, with {@code cacheCreation1h} of the {@code cacheCreation}
     * tokens written at the 1-hour TTL and the rest at 5-minute. Clamped to
     * {@code cacheCreation}, so a malformed split can never price more
     * writes than were made.
     */
    public static BigDecimal costFor(
            String model, long input, long output, long cacheRead, long cacheCreation, long cacheCreation1h) {
        ModelPrice p = priceFor(model);
        long oneHour = Math.max(0, Math.min(cacheCreation1h, cacheCreation));
        long fiveMinute = cacheCreation - oneHour;
        BigDecimal usd = BigDecimal.valueOf(input)
                .multiply(p.input())
                .add(BigDecimal.valueOf(output).multiply(p.output()))
                .add(BigDecimal.valueOf(cacheRead).multiply(p.cacheRead()))
                .add(BigDecimal.valueOf(fiveMinute).multiply(p.input()).multiply(CACHE_WRITE_5M_MULTIPLIER))
                .add(BigDecimal.valueOf(oneHour).multiply(p.input()).multiply(CACHE_WRITE_1H_MULTIPLIER));
        return usd.divide(ONE_MILLION, INTERMEDIATE_SCALE, RoundingMode.HALF_UP);
    }
}
