package com.coc.docs.claudeusage;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes the activity tables (V60, ADR-074):
 * {@code claude_usage_daily_activity} and its two children,
 * {@code claude_usage_daily_project} and {@code claude_usage_activity_session}.
 */
@Repository
public class ClaudeUsageActivityRepository {

    private final JdbcClient jdbc;

    public ClaudeUsageActivityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Replaces the member's activity for {@code usageDate} with {@code activity},
     * or just removes it when {@code activity} is null. Delete-then-insert, for
     * the same reason as {@link ClaudeUsageDailyModelRepository#replaceDay}: a
     * project or session absent from this report must not survive from an
     * earlier one. Deleting the day row cascades to both child tables. Runs
     * inside {@code recordUsage}'s transaction.
     */
    public void replaceDay(UUID memberId, LocalDate usageDate, ClaudeUsageDayActivity activity) {
        jdbc.sql("delete from claude_usage_daily_activity where member_id = ? and usage_date = ?")
                .params(memberId, usageDate)
                .update();
        if (activity == null) {
            return;
        }

        jdbc.sql("""
                        insert into claude_usage_daily_activity
                          (member_id, usage_date, request_count, api_call_count, conversation_count,
                           session_count, active_seconds)
                        values (?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(memberId, usageDate, activity.requestCount(), activity.apiCallCount(),
                        activity.conversationCount(), activity.sessionCount(), activity.activeSeconds())
                .update();

        for (ClaudeUsageDayActivity.Project p : activity.projects()) {
            jdbc.sql("""
                            insert into claude_usage_daily_project
                              (member_id, usage_date, project, request_count, api_call_count, conversation_count,
                               session_count, active_seconds, input_tokens, output_tokens, cache_read_tokens,
                               cache_creation_tokens, first_at, last_at)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(memberId, usageDate, p.name(), p.requestCount(), p.apiCallCount(), p.conversationCount(),
                            p.sessionCount(), p.activeSeconds(), p.inputTokens(), p.outputTokens(), p.cacheReadTokens(),
                            p.cacheCreationTokens(), timestamp(p.firstAt()), timestamp(p.lastAt()))
                    .update();
        }

        for (ClaudeUsageDayActivity.Session s : activity.sessions()) {
            jdbc.sql("""
                            insert into claude_usage_activity_session
                              (member_id, usage_date, started_at, ended_at, request_count, projects)
                            values (?, ?, ?, ?, ?, ?)
                            """)
                    .params(memberId, usageDate, timestamp(s.startedAt()), timestamp(s.endedAt()), s.requestCount(),
                            s.projects().toArray(String[]::new))
                    .update();
        }
    }

    /**
     * One member's activity for every day in {@code [from, to]} that has any,
     * keyed and ordered by date. A day missing from the map was not reported
     * with activity — never the same as a day of zero.
     */
    public Map<LocalDate, ClaudeUsageDayActivity> listInRangeForMember(UUID memberId, LocalDate from, LocalDate to) {
        Map<LocalDate, DayRow> days = new TreeMap<>();
        jdbc.sql("""
                        select usage_date, request_count, api_call_count, conversation_count, session_count, active_seconds
                        from claude_usage_daily_activity
                        where member_id = ? and usage_date between ? and ?
                        """)
                .params(memberId, from, to)
                .query(rs -> {
                    days.put(rs.getObject("usage_date", LocalDate.class), new DayRow(
                            rs.getInt("request_count"), rs.getInt("api_call_count"), rs.getInt("conversation_count"),
                            rs.getInt("session_count"), rs.getLong("active_seconds"),
                            new ArrayList<>(), new ArrayList<>()));
                });
        if (days.isEmpty()) {
            return Map.of();
        }

        jdbc.sql("""
                        select usage_date, project, request_count, api_call_count, conversation_count, session_count,
                               active_seconds, input_tokens, output_tokens, cache_read_tokens, cache_creation_tokens,
                               first_at, last_at
                        from claude_usage_daily_project
                        where member_id = ? and usage_date between ? and ?
                        order by usage_date, input_tokens + output_tokens desc, project
                        """)
                .params(memberId, from, to)
                .query(rs -> {
                    DayRow day = days.get(rs.getObject("usage_date", LocalDate.class));
                    if (day != null) {
                        day.projects().add(mapProject(rs));
                    }
                });

        jdbc.sql("""
                        select usage_date, started_at, ended_at, request_count, projects
                        from claude_usage_activity_session
                        where member_id = ? and usage_date between ? and ?
                        order by started_at
                        """)
                .params(memberId, from, to)
                .query(rs -> {
                    DayRow day = days.get(rs.getObject("usage_date", LocalDate.class));
                    if (day != null) {
                        day.sessions().add(mapSession(rs));
                    }
                });

        Map<LocalDate, ClaudeUsageDayActivity> out = new TreeMap<>();
        days.forEach((date, d) -> out.put(date, new ClaudeUsageDayActivity(
                d.requestCount(), d.apiCallCount(), d.conversationCount(), d.sessionCount(), d.activeSeconds(),
                d.projects(), d.sessions())));
        return out;
    }

    private record DayRow(
            int requestCount,
            int apiCallCount,
            int conversationCount,
            int sessionCount,
            long activeSeconds,
            List<ClaudeUsageDayActivity.Project> projects,
            List<ClaudeUsageDayActivity.Session> sessions) {}

    private static ClaudeUsageDayActivity.Project mapProject(ResultSet rs) throws SQLException {
        return new ClaudeUsageDayActivity.Project(
                rs.getString("project"),
                rs.getInt("request_count"),
                rs.getInt("api_call_count"),
                rs.getInt("conversation_count"),
                rs.getInt("session_count"),
                rs.getLong("active_seconds"),
                rs.getLong("input_tokens"),
                rs.getLong("output_tokens"),
                rs.getLong("cache_read_tokens"),
                rs.getLong("cache_creation_tokens"),
                instant(rs, "first_at"),
                instant(rs, "last_at"));
    }

    private static ClaudeUsageDayActivity.Session mapSession(ResultSet rs) throws SQLException {
        Array array = rs.getArray("projects");
        List<String> projects = array == null ? List.of() : List.of((String[]) array.getArray());
        return new ClaudeUsageDayActivity.Session(
                instant(rs, "started_at"), instant(rs, "ended_at"), rs.getInt("request_count"), projects);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime timestamp(Instant at) {
        return at == null ? null : at.atOffset(ZoneOffset.UTC);
    }
}
