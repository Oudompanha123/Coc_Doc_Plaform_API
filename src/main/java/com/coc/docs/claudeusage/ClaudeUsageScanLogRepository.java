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
 *
 * <p>A row may start {@code running} — inserted by the reporter's start
 * message, finished by its result (V80, ADR-107).
 */
@Repository
public class ClaudeUsageScanLogRepository {

    /**
     * The display name is worked out at read time, so a rename shows on older
     * logs too: the platform account's name for that email, else the roster's
     * stored one ({@link ClaudeUsageMemberRepository#DISPLAY_NAME}, same rule).
     */
    private static final String SELECT = """
            select l.id, l.client_scan_id, l.member_id, l.email,
                   coalesce(
                     (select nullif(btrim(a.display_name), '') from account a
                       where lower(a.email) = lower(l.email) limit 1),
                     m.display_name) as display_name,
                   l.trigger, l.cli_version,
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
                toInstant(rs.getObject("finished_at", OffsetDateTime.class)),
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

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /**
     * Stores a scan's start as a {@code running} row with no steps (ADR-107).
     * Returns false when {@code clientScanId} is already stored — its result
     * got here first, or the start was resent — and changes nothing then.
     */
    public boolean insertRunning(ClaudeUsageScanLog log) {
        return jdbc.sql("""
                        insert into claude_usage_scan_log (
                          client_scan_id, member_id, email, trigger, cli_version, started_at, finished_at, status,
                          upload_status, steps)
                        values (?, ?, ?, ?, ?, ?, null, 'running', 'not-attempted', '[]'::jsonb)
                        on conflict (client_scan_id) do nothing
                        """)
                .params(
                        log.clientScanId(),
                        log.memberId(),
                        log.email(),
                        log.trigger(),
                        log.cliVersion(),
                        Timestamp.from(log.startedAt()))
                .update() == 1;
    }

    /**
     * Stores one scan's result: a new row, or the completion of the row its
     * start message left {@code running} (ADR-107). Returns false when the
     * result is already stored — a CLI resending a queued record — and
     * changes nothing then: a finished row is never overwritten.
     */
    public boolean insert(ClaudeUsageScanLog log, String stepsJson) {
        return jdbc.sql("""
                        insert into claude_usage_scan_log (
                          client_scan_id, member_id, email, trigger, cli_version, started_at, finished_at, status,
                          upload_status, transcript_files, responses, duplicates, new_tokens, days_uploaded,
                          error_step, error_message, steps)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                        on conflict (client_scan_id) do update set
                          member_id = excluded.member_id, trigger = excluded.trigger,
                          cli_version = excluded.cli_version, started_at = excluded.started_at,
                          finished_at = excluded.finished_at, received_at = now(), status = excluded.status,
                          upload_status = excluded.upload_status, transcript_files = excluded.transcript_files,
                          responses = excluded.responses, duplicates = excluded.duplicates,
                          new_tokens = excluded.new_tokens, days_uploaded = excluded.days_uploaded,
                          error_step = excluded.error_step, error_message = excluded.error_message,
                          steps = excluded.steps
                        where claude_usage_scan_log.status = 'running'
                          and claude_usage_scan_log.email = excluded.email
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

    /** One email's scans that started in {@code [from, to)}, newest first, at most {@code limit} — steps left out. */
    public List<ClaudeUsageScanLog> listForEmail(String email, Instant from, Instant to, int limit) {
        return jdbc.sql(SELECT + """
                         where l.email = ? and l.started_at >= ? and l.started_at < ?
                         order by l.started_at desc limit ?
                        """)
                .params(email, Timestamp.from(from), Timestamp.from(to), limit)
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
