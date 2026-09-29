package com.coc.docs.notify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The stored body of an HRMS report (V49, ADR-057).
 *
 * <p>Separate from {@link HrmsNotificationRepository} because the two tables
 * answer different questions and were widened at different times: that one
 * records that a report arrived and is the narrow shape {@code V21} insists
 * on; this one holds the attendance itself, which {@code V21} said would need
 * a decision of its own. Keeping them apart is what lets V21's comment stay
 * literally true.
 *
 * <p>No delete method. Retention is the {@code on delete cascade} from
 * {@code hrms_notification}, so pruning arrivals prunes bodies with them and
 * there is no second schedule to keep in step.
 *
 * <p>// req: FR-OPS-001, NFR-SEC-PII, ADR-057
 */
@Repository
public class HrmsReportContentRepository {

    private static final TypeReference<List<HrmsMailContent.Row>> ROWS = new TypeReference<>() {};
    private static final TypeReference<List<HrmsMailContent.Detail>> DETAILS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public HrmsReportContentRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Stores a parsed body, replacing any already held for this report.
     *
     * <p>An upsert rather than an insert: re-parsing after a parser fix should
     * correct what is stored, not fail on the primary key and leave the old
     * reading in place.
     *
     * <p>The whole report is written, unscoped. Narrowing happens on read, in
     * {@code HrmsNotificationService.reportFor} — storing a per-caller view
     * would mean one copy per reader of the same HR data, which is the
     * opposite of what NFR-SEC-PII wants.
     */
    public void save(long notificationId, HrmsMailContent content) {
        jdbc.sql("""
                        insert into hrms_report_content
                          (notification_id, department, rows, details, note, parsed_at)
                        values (?, ?, ?::jsonb, ?::jsonb, ?, now())
                        on conflict (notification_id) do update set
                          department = excluded.department,
                          rows       = excluded.rows,
                          details    = excluded.details,
                          note       = excluded.note,
                          parsed_at  = excluded.parsed_at
                        """)
                .params(
                        notificationId,
                        content.department(),
                        write(content.rows()),
                        write(content.details()),
                        content.note())
                .update();
    }

    /**
     * The stored body, or empty when this report predates {@code V49} or was
     * never parsed.
     *
     * <p>Empty is an ordinary answer, not an error: the ten reports recorded
     * before this table existed have no body here, and the caller falls back
     * to the mailbox for those rather than telling somebody the report is
     * gone.
     */
    public Optional<HrmsMailContent> findByNotificationId(long notificationId, String subject, java.time.LocalDate reportDate) {
        return jdbc.sql("select department, rows, details, note from hrms_report_content where notification_id = ?")
                .param(notificationId)
                .query((rs, rowNum) -> new HrmsMailContent(
                        subject,
                        reportDate,
                        rs.getString("department"),
                        read(rs.getString("rows"), ROWS),
                        read(rs.getString("details"), DETAILS),
                        rs.getString("note")))
                .optional();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // Unreachable for these records, and not worth a checked signature
            // on every caller. If it ever happens the report is unstorable and
            // the poll should fail loudly rather than store half of it.
            throw new IllegalStateException("could not serialise an HRMS report body", e);
        }
    }

    private <T> T read(String raw, TypeReference<T> type) {
        try {
            return json.readValue(raw, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not read a stored HRMS report body", e);
        }
    }
}
