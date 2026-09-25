package com.coc.docs.claudeusage;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads and writes {@code claude_usage_daily} rows (V22, ADR-035). */
@Repository
public class ClaudeUsageDailyRepository {

    private final JdbcClient jdbc;

    public ClaudeUsageDailyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private ClaudeUsageDaily map(ResultSet rs, int rowNum) throws SQLException {
        return new ClaudeUsageDaily(
                rs.getObject("id", UUID.class),
                rs.getObject("member_id", UUID.class),
                rs.getObject("usage_date", LocalDate.class),
                rs.getLong("input_tokens"),
                rs.getLong("output_tokens"),
                rs.getLong("cache_read_tokens"),
                rs.getLong("cache_creation_tokens"),
                rs.getBigDecimal("cost_usd"),
                rs.getString("models"),
                rs.getObject("reported_at", OffsetDateTime.class).toInstant());
    }

    /**
     * Every day, for every member, whose {@code usage_date} falls in
     * {@code [from, to]} inclusive -- the whole set the dashboard's rollup
     * (per-user totals, the daily chart) is built from in one query.
     */
    public List<ClaudeUsageDaily> listInRange(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        select id, member_id, usage_date, input_tokens, output_tokens,
                               cache_read_tokens, cache_creation_tokens, cost_usd, models, reported_at
                        from claude_usage_daily
                        where usage_date between ? and ?
                        order by usage_date
                        """)
                .params(from, to)
                .query(this::map)
                .list();
    }

    /**
     * As {@link #listInRange}, scoped to one member — the personal "My Claude
     * Usage" screen's own history (ADR-047). A dedicated query rather than
     * filtering {@code listInRange}'s result in Java: the caller only ever
     * needs their own rows, so there is no reason to pull every other
     * member's data out of the database first.
     */
    public List<ClaudeUsageDaily> listInRangeForMember(UUID memberId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        select id, member_id, usage_date, input_tokens, output_tokens,
                               cache_read_tokens, cache_creation_tokens, cost_usd, models, reported_at
                        from claude_usage_daily
                        where member_id = ? and usage_date between ? and ?
                        order by usage_date
                        """)
                .params(memberId, from, to)
                .query(this::map)
                .list();
    }

    /**
     * Idempotent upsert keyed on {@code (member_id, usage_date)} (ADR-035
     * decision 3): the reporter always sends the day's whole running total, so
     * a replay overwrites rather than adds on top of what is already there.
     *
     * @return true when this created the row, false when it overwrote an
     *     existing one -- {@code xmax = 0} is the standard Postgres tell for
     *     "this tuple has never been touched by another command," true only
     *     for a row this very statement just inserted.
     */
    public boolean upsert(
            UUID memberId,
            LocalDate usageDate,
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheCreationTokens,
            BigDecimal costUsd,
            String models) {

        Boolean inserted = jdbc.sql(
                        """
                        insert into claude_usage_daily
                          (member_id, usage_date, input_tokens, output_tokens,
                           cache_read_tokens, cache_creation_tokens, cost_usd, models, reported_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, now())
                        on conflict (member_id, usage_date) do update
                          set input_tokens = excluded.input_tokens,
                              output_tokens = excluded.output_tokens,
                              cache_read_tokens = excluded.cache_read_tokens,
                              cache_creation_tokens = excluded.cache_creation_tokens,
                              cost_usd = excluded.cost_usd,
                              models = excluded.models,
                              reported_at = excluded.reported_at
                        returning (xmax = 0) as inserted
                        """)
                .params(
                        memberId,
                        usageDate,
                        inputTokens,
                        outputTokens,
                        cacheReadTokens,
                        cacheCreationTokens,
                        costUsd,
                        models)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(inserted);
    }
}
