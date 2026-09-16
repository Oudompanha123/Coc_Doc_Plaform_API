package com.coc.docs.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The HRMS poller's decisions, with no mailbox and no database.
 *
 * <p>{@link HrmsImapMailbox} is an interface implementation precisely so these
 * can be asserted without Google: what counts as new, where a search starts,
 * and what a subject line means are all decided in
 * {@link HrmsNotificationService} and none of them need a network.
 *
 * <p>// req: FR-OPS-001, NFR-SEC-PII
 */
class HrmsNotificationServiceTest {

    private final HrmsMailbox mailbox = mock(HrmsMailbox.class);
    private final HrmsNotificationRepository repository = mock(HrmsNotificationRepository.class);

    private static HrmsMailProperties properties(boolean enabled, String username, String sender) {
        return new HrmsMailProperties(
                enabled,
                "imap.gmail.com",
                993,
                username,
                username.isBlank() ? "" : "app-password",
                sender,
                "INBOX",
                Duration.ofDays(7),
                Duration.ofDays(90));
    }

    private static HrmsMailProperties configured() {
        return properties(true, "someone@gmail.com", "noreply-hrms@wecambodia.com");
    }

    private HrmsNotificationService serviceWith(HrmsMailProperties props) {
        return new HrmsNotificationService(mailbox, repository, props);
    }

    private static HrmsMailbox.Arrival arrival(String id, String subject) {
        return new HrmsMailbox.Arrival(id, subject, Instant.parse("2026-09-08T05:02:00Z"));
    }

    @Nested
    @DisplayName("reading the report date out of the subject")
    class SubjectParsing {

        @Test
        @DisplayName("reads the date HRMS actually sends")
        void readsTheRealSubject() {
            assertThat(HrmsNotificationService.parseReportDate("Attendance on 08-Sep-2026"))
                    .contains(LocalDate.of(2026, 9, 8));
        }

        @Test
        @DisplayName("accepts a single-digit day")
        void acceptsSingleDigitDay() {
            assertThat(HrmsNotificationService.parseReportDate("Attendance on 8-Sep-2026"))
                    .contains(LocalDate.of(2026, 9, 8));
        }

        @Test
        @DisplayName("still finds the date when HRMS rewords around it")
        void survivesRewording() {
            assertThat(HrmsNotificationService.parseReportDate("[KOSIGN] Attendance report for 08-Sep-2026 (BeplePay)"))
                    .contains(LocalDate.of(2026, 9, 8));
        }

        /**
         * The reason {@code Locale.ENGLISH} is pinned in the formatter. The
         * default locale on the machines in this project is Korean — the same
         * setting behind CONST-OPS-01 — and a {@code MMM} pattern parses month
         * names in whatever locale it is handed. Without the pin this test
         * fails and, in production, every report silently gets a null date.
         */
        @Test
        @DisplayName("parses an English month name under a Korean default locale")
        void parsesUnderKoreanDefaultLocale() {
            Locale original = Locale.getDefault();
            try {
                Locale.setDefault(Locale.KOREA);
                assertThat(HrmsNotificationService.parseReportDate("Attendance on 08-Sep-2026"))
                        .contains(LocalDate.of(2026, 9, 8));
            } finally {
                Locale.setDefault(original);
            }
        }

        @Test
        @DisplayName("gives up rather than guessing when there is no date")
        void noDateIsEmpty() {
            assertThat(HrmsNotificationService.parseReportDate("Attendance report")).isEmpty();
            assertThat(HrmsNotificationService.parseReportDate("")).isEmpty();
            assertThat(HrmsNotificationService.parseReportDate(null)).isEmpty();
        }

        @Test
        @DisplayName("gives up on a date-shaped string that is not a date")
        void impossibleDateIsEmpty() {
            assertThat(HrmsNotificationService.parseReportDate("Attendance on 31-Feb-2026")).isEmpty();
            assertThat(HrmsNotificationService.parseReportDate("Attendance on 08-Xyz-2026")).isEmpty();
        }
    }

    @Nested
    @DisplayName("polling")
    class Polling {

        @Test
        @DisplayName("never opens a mailbox while disabled")
        void disabledPollsNothing() {
            assertThat(serviceWith(properties(false, "someone@gmail.com", "hrms@example.com")).poll())
                    .isZero();
            verifyNoInteractions(mailbox);
        }

        /**
         * Enabled but half-configured is a mistake, not a decision, and it must
         * not be treated as "on" — an IMAP connect with a blank password is a
         * failed authentication against a real account, repeated every thirty
         * minutes, which is how a mailbox gets locked.
         */
        @Test
        @DisplayName("never opens a mailbox while enabled but incomplete")
        void incompletePollsNothing() {
            assertThat(serviceWith(properties(true, "", "hrms@example.com")).poll()).isZero();
            verifyNoInteractions(mailbox);
        }

        @Test
        @DisplayName("records an arrival it has not seen")
        void recordsNewArrival() {
            when(mailbox.arrivalsSince(any())).thenReturn(List.of(arrival("<a@hrms>", "Attendance on 08-Sep-2026")));
            when(repository.record(any())).thenReturn(true);

            assertThat(serviceWith(configured()).poll()).isEqualTo(1);

            ArgumentCaptor<HrmsNotification> written = ArgumentCaptor.forClass(HrmsNotification.class);
            verify(repository).record(written.capture());
            assertThat(written.getValue().messageId()).isEqualTo("<a@hrms>");
            assertThat(written.getValue().subject()).isEqualTo("Attendance on 08-Sep-2026");
            assertThat(written.getValue().reportDate()).isEqualTo(LocalDate.of(2026, 9, 8));
        }

        /**
         * The poller re-reads an overlapping window every run, so seeing the
         * same message again is the ordinary case. What must not happen is
         * counting it as news.
         */
        @Test
        @DisplayName("does not count an arrival the database already had")
        void duplicateIsNotNews() {
            when(mailbox.arrivalsSince(any())).thenReturn(List.of(arrival("<a@hrms>", "Attendance on 08-Sep-2026")));
            when(repository.record(any())).thenReturn(false);

            assertThat(serviceWith(configured()).poll()).isZero();
        }

        @Test
        @DisplayName("stores a null report date rather than dropping an unparseable subject")
        void unparseableSubjectStillRecorded() {
            when(mailbox.arrivalsSince(any())).thenReturn(List.of(arrival("<b@hrms>", "Attendance summary")));
            when(repository.record(any())).thenReturn(true);

            assertThat(serviceWith(configured()).poll()).isEqualTo(1);

            ArgumentCaptor<HrmsNotification> written = ArgumentCaptor.forClass(HrmsNotification.class);
            verify(repository).record(written.capture());
            assertThat(written.getValue().reportDate()).isNull();
            assertThat(written.getValue().summary()).isEqualTo("Attendance summary");
        }
    }

    @Nested
    @DisplayName("where a search starts")
    class SearchWindow {

        @Test
        @DisplayName("anchors to the newest arrival already recorded, not to now")
        void anchorsToLatest() {
            Instant latest = Instant.parse("2026-09-08T05:02:00Z");
            when(repository.latestReceivedAt()).thenReturn(Optional.of(latest));

            assertThat(serviceWith(configured()).searchFrom()).isEqualTo(latest.minus(Duration.ofDays(7)));
        }

        /**
         * The case that makes anchoring worth the trouble: after an outage
         * longer than the lookback window, a search starting at "now minus the
         * window" would skip every report sent while the platform was down.
         */
        @Test
        @DisplayName("reaches back past an outage longer than the window")
        void reachesBackPastAnOutage() {
            Instant longAgo = Instant.parse("2026-08-01T05:02:00Z");
            when(repository.latestReceivedAt()).thenReturn(Optional.of(longAgo));

            assertThat(serviceWith(configured()).searchFrom()).isBefore(Instant.parse("2026-08-01T00:00:00Z"));
        }

        @Test
        @DisplayName("falls back to the window before now on an empty table")
        void emptyTableLooksBackFromNow() {
            when(repository.latestReceivedAt()).thenReturn(Optional.empty());

            Instant before = Instant.now().minus(Duration.ofDays(7));
            Instant from = serviceWith(configured()).searchFrom();

            assertThat(from).isBetween(before.minusSeconds(60), before.plusSeconds(60));
        }
    }

    @Nested
    @DisplayName("retention")
    class Retention {

        @Test
        @DisplayName("prunes by the configured window")
        void prunesByWindow() {
            when(repository.deleteReceivedBefore(any())).thenReturn(3);

            assertThat(serviceWith(configured()).prune()).isEqualTo(3);

            ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
            verify(repository).deleteReceivedBefore(cutoff.capture());
            assertThat(cutoff.getValue()).isBefore(Instant.now().minus(Duration.ofDays(89)));
        }

        @Test
        @DisplayName("asking for nothing reads nothing")
        void nonPositiveLimitReadsNothing() {
            assertThat(serviceWith(configured()).recent(0)).isEmpty();
            verify(repository, never()).recent(org.mockito.ArgumentMatchers.anyInt());
        }
    }
}
