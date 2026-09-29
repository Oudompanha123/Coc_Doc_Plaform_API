package com.coc.docs.claudeusage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads and writes {@code claude_usage_daily_model} rows (V49, ADR-058). */
@Repository
public class ClaudeUsageDailyModelRepository {

    private final JdbcClient jdbc;

    public ClaudeUsageDailyModelRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private ClaudeUsageDailyModel map(ResultSet rs, int rowNum) throws SQLException {
        return new ClaudeUsageDailyModel(
                rs.getObject("member_id", UUID.class),
                rs.getObject("usage_date", LocalDate.class),
                rs.getString("model"),
                rs.getLong("input_tokens"),
                rs.getLong("output_tokens"),
                rs.getLong("cache_read_tokens"),
                rs.getLong("cache_creation_tokens"),
                rs.getLong("cache_creation_1h_tokens"),
                rs.getBigDecimal("cost_usd"));
    }

    /**
     * Replaces the member's whole per-model set for {@code usageDate} with
     * {@code rows}. Delete-then-insert rather than a per-row upsert: the
     * reporter sends the day's running total, so a model that appeared in an
     * earlier report but not this one must disappear too, or a replay could
     * leave a stale row behind. Runs inside {@code recordUsage}'s transaction,
     * alongside the {@code claude_usage_daily} upsert it details.
     */
    public void replaceDay(UUID memberId, LocalDate usageDate, List<ClaudeUsageDailyModel> rows) {
        jdbc.sql("delete from claude_usage_daily_model where member_id = ? and usage_date = ?")
                .params(memberId, usageDate)
                .update();
        for (ClaudeUsageDailyModel row : rows) {
            jdbc.sql("""
                            insert into claude_usage_daily_model
                              (member_id, usage_date, model, input_tokens, output_tokens,
                               cache_read_tokens, cache_creation_tokens, cache_creation_1h_tokens, cost_usd)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(
                            memberId,
                            usageDate,
                            row.model(),
                            row.inputTokens(),
                            row.outputTokens(),
                            row.cacheReadTokens(),
                            row.cacheCreationTokens(),
                            row.cacheCreation1hTokens(),
                            row.costUsd())
                    .update();
        }
    }

    /** One member's per-model rows with {@code usage_date} in {@code [from, to]}, newest day first. */
    public List<ClaudeUsageDailyModel> listInRangeForMember(UUID memberId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        select member_id, usage_date, model, input_tokens, output_tokens,
                               cache_read_tokens, cache_creation_tokens, cache_creation_1h_tokens, cost_usd
                        from claude_usage_daily_model
                        where member_id = ? and usage_date between ? and ?
                        order by usage_date desc, cost_usd desc, model
                        """)
                .params(memberId, from, to)
                .query(this::map)
                .list();
    }
}
