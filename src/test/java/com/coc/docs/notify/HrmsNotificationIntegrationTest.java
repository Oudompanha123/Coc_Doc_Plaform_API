package com.coc.docs.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.coc.docs.PostgresTestBase;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code hrms_notification} against a real PostgreSQL.
 *
 * <p>The thing worth testing here is the unique index on {@code message_id},
 * because the whole design leans on it: the poller keeps no bookmark, re-reads
 * an overlapping window of the mailbox every run, and relies on
 * {@code on conflict do nothing} to keep that from announcing the same report
 * repeatedly. That is a database behaviour, not a Java one, and a mock cannot
 * be wrong about it in the way a real constraint can.
 *
 * <p>This also applies {@code V21__hrms_notification.sql} — a migration that
 * does not apply cleanly fails every integration test at once.
 *
 * <p>// req: FR-OPS-001, NFR-SEC-PII
 */
class HrmsNotificationIntegrationTest extends PostgresTestBase {

    @Autowired HrmsNotificationRepository repository;
    @Autowired JdbcClient jdbc;

    private static final Instant MONDAY = Instant.parse("2026-09-07T01:02:00Z");
    private static final Instant TUESDAY = Instant.parse("2026-09-08T01:02:00Z");

    @BeforeEach
    void clear() {
        jdbc.sql("delete from hrms_notification").update();
    }

    private static HrmsNotification report(String messageId, String subject, LocalDate day, Instant at) {
        return HrmsNotification.unsaved(messageId, subject, day, at);
    }

    @Test
    @DisplayName("records an arrival")
    void recordsAnArrival() {
        boolean written = repository.record(
                report("<a@hrms>", "Attendance on 08-Sep-2026", LocalDate.of(2026, 9, 8), TUESDAY));

        assertThat(written).isTrue();
        assertThat(repository.recent(10)).hasSize(1);
    }

    /**
     * The constraint the poller depends on. Not "the second call throws" —
     * {@code on conflict do nothing} means it must return quietly and report
     * that nothing was new, because the poller treats an exception as a failure
     * worth logging and a {@code false} as the ordinary case.
     */
    @Test
    @DisplayName("refuses a second copy of the same message without raising")
    void dedupesOnMessageId() {
        assertThat(repository.record(report("<a@hrms>", "Attendance on 08-Sep-2026", null, TUESDAY)))
                .isTrue();
        assertThat(repository.record(report("<a@hrms>", "Attendance on 08-Sep-2026", null, TUESDAY)))
                .isFalse();

        assertThat(repository.recent(10)).hasSize(1);
    }

    @Test
    @DisplayName("keeps a null report date rather than refusing the row")
    void nullReportDateRoundTrips() {
        repository.record(report("<b@hrms>", "Attendance summary", null, TUESDAY));

        assertThat(repository.recent(10)).singleElement().satisfies(row -> {
            assertThat(row.reportDate()).isNull();
            assertThat(row.subject()).isEqualTo("Attendance summary");
        });
    }

    @Test
    @DisplayName("returns arrivals newest first")
    void newestFirst() {
        repository.record(report("<mon@hrms>", "Attendance on 07-Sep-2026", LocalDate.of(2026, 9, 7), MONDAY));
        repository.record(report("<tue@hrms>", "Attendance on 08-Sep-2026", LocalDate.of(2026, 9, 8), TUESDAY));

        assertThat(repository.recent(10))
                .extracting(HrmsNotification::messageId)
                .containsExactly("<tue@hrms>", "<mon@hrms>");
    }

    @Test
    @DisplayName("reports the newest arrival's time, and nothing on an empty table")
    void latestReceivedAt() {
        assertThat(repository.latestReceivedAt()).isEmpty();

        repository.record(report("<mon@hrms>", "Attendance on 07-Sep-2026", null, MONDAY));
        repository.record(report("<tue@hrms>", "Attendance on 08-Sep-2026", null, TUESDAY));

        assertThat(repository.latestReceivedAt()).contains(TUESDAY);
    }

    /**
     * The half of the design that justifies not putting these in
     * {@code audit_log}: they can be deleted. An audit row cannot — the schema
     * revokes {@code delete} from {@code public}.
     */
    @Test
    @DisplayName("deletes arrivals past the retention cutoff, and only those")
    void prunesByCutoff() {
        repository.record(report("<mon@hrms>", "Attendance on 07-Sep-2026", null, MONDAY));
        repository.record(report("<tue@hrms>", "Attendance on 08-Sep-2026", null, TUESDAY));

        int deleted = repository.deleteReceivedBefore(TUESDAY);

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.recent(10))
                .extracting(HrmsNotification::messageId)
                .containsExactly("<tue@hrms>");
    }

    /**
     * Subjects are not guaranteed ASCII — this platform's content is Khmer and
     * Korean, and a report from a Cambodian HR system may be either. The
     * encoding has to survive the round trip through {@code text}, which is the
     * same UTF-8-everywhere rule the rest of the schema follows.
     */
    @Test
    @DisplayName("round-trips a non-ASCII subject")
    void nonAsciiSubject() {
        String subject = "វត្តមាន / 출근 08-Sep-2026";
        repository.record(report("<khm@hrms>", subject, LocalDate.of(2026, 9, 8), TUESDAY));

        assertThat(repository.recent(10)).singleElement().extracting(HrmsNotification::subject).isEqualTo(subject);
    }

}
