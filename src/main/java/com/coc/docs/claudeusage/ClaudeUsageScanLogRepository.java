package com.coc.docs.claudeusage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code claude_usage_scan_log} rows (V66, ADR-099). The
 * steps column is JSON text here; {@link ClaudeUsageScanLogService} owns its
 * shape.
 */
@Repository
public class ClaudeUsageScanLogRepository {

    /** The display name comes from the roster at read time, so a rename shows on older logs too. */
    private static final String SELECT = """
            select l.id, l.client_scan_id, l.member_id, l.email, m.display_name, l.trigger, l.cli_version,
                   l.started_at, l.finished_at, l.received_at, l.status, l.upload_status, l.transcript_files,
                   l.responses, l.duplicates, l.new_tokens, l.days_uploaded, l.error_step, l.error_message
            from claude_usage_scan_log l
            left join claude_usage_member m on m.id = l.member_id
            """;

    private final JdbcClient jdbc;

    public ClaudeUsageScanLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static ClaudeUsageScanLog map(ResultSet rs, int rowNum) throws SQLException {
        return new ClaudeUsageScanLog(
                rs.getObject("id", UUID.class),
                rs.getObject("client_scan_id", UUID.class),
                rs.getObject("member_id", UUID.class),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("trigger"),
                rs.getString("cli_version"),
                rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                rs.getObject("finished_at", OffsetDateTime.class).toInstant(),
                rs.getObject("received_at", OffsetDateTime.class).toInstant(),
                rs.getString("status"),
                rs.getString("upload_status"),
                (Integer) rs.getObject("transcript_files"),
                (Integer) rs.getObject("responses"),
                (Integer) rs.getObject("duplicates"),
                (Long) rs.getObject("new_tokens"),
                (Integer) rs.getObject("days_uploaded"),
                rs.getString("error_step"),
                rs.getString("error_message"),
                List.of());
    }

    /**
     * Stores one record. Returns false when {@code clientScanId} is already
     * stored — a CLI resending a queued record — and changes nothing then.
     */
    public boolean insert(ClaudeUsageScanLog log, String stepsJson) {
        return jdbc.sql("""
                        insert into claude_usage_scan_log (
                          client_scan_id, member_id, email, trigger, cli_version, started_at, finished_at, status,
                          upload_status, transcript_files, responses, duplicates, new_tokens, days_uploaded,
                          error_step, error_message, steps)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                        on conflict (client_scan_id) do nothing
                        """)
                .params(
                        log.clientScanId(),
                        log.memberId(),
                        log.email(),
                        log.trigger(),
                        log.cliVersion(),
                        Timestamp.from(log.startedAt()),
                        Timestamp.from(log.finishedAt()),
                        log.status(),
                        log.uploadStatus(),
                        log.transcriptFiles(),
                        log.responses(),
                        log.duplicates(),
                        log.newTokens(),
                        log.daysUploaded(),
                        log.errorStep(),
                        log.errorMessage(),
                        stepsJson)
                .update() == 1;
    }

    /** Scans that started in {@code [from, to)}, newest first, at most {@code limit} — steps left out. */
    public List<ClaudeUsageScanLog> list(Instant from, Instant to, int limit) {
        return jdbc.sql(SELECT + " where l.started_at >= ? and l.started_at < ? order by l.started_at desc limit ?")
                .params(Timestamp.from(from), Timestamp.from(to), limit)
                .query(ClaudeUsageScanLogRepository::map)
                .list();
    }

    public Optional<ClaudeUsageScanLog> findById(UUID id) {
        return jdbc.sql(SELECT + " where l.id = ?")
                .param(id)
                .query(ClaudeUsageScanLogRepository::map)
                .optional();
    }

    /** The steps column as stored, for {@link #findById}'s row. */
    public Optional<String> stepsJson(UUID id) {
        return jdbc.sql("select steps::text from claude_usage_scan_log where id = ?")
                .param(id)
                .query(String.class)
                .optional();
    }

    /** Drops records that started before {@code cutoff}; returns how many. */
    public int deleteStartedBefore(Instant cutoff) {
        return jdbc.sql("delete from claude_usage_scan_log where started_at < ?")
                .param(Timestamp.from(cutoff))
                .update();
    }
}
