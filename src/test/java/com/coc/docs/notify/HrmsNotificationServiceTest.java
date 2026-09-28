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

    /** Only reportFor() consults it; every other test here leaves it unstubbed. */
    private final com.coc.docs.account.AccountRepository accounts =
            org.mockito.Mockito.mock(com.coc.docs.account.AccountRepository.class);

    private final HrmsReportContentRepository contents = mock(HrmsReportContentRepository.class);

    private HrmsNotificationService serviceWith(HrmsMailProperties props) {
        return new HrmsNotificationService(mailbox, repository, props, accounts, contents);
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

    /*
     * Who sees whose attendance. The route is floored at USER, so reaching it
     * proves nothing about scope -- the narrowing is these tests, and getting
     * it wrong discloses one employee's clock-in times to another.
     * // req: FR-OPS-001, NFR-SEC-PII, ADR-042
     */
    @Nested
    class ReportScope {

        private static final String HTML =
                "<h3><span>BeplePay Department</span></h3><table>"
                        + "<tr><th>Name</th><th>Yesterday Check-Out</th><th>Today Check-In</th></tr>"
                        + "<tr><td>Nin Srey Nuth</td>"
                        + "<td><span>17:00</span> -&gt; <span>17:44</span></td><td><div><span>Normal</span></div></td>"
                        + "<td><span>08:00</span> -&gt; <span>08:01</span></td><td><div><span>Late Check-In</span></div></td></tr>"
                        + "<tr><td>Ken Vantha</td>"
                        + "<td><span>17:00</span> -&gt; <span>00:00</span></td><td><div><span>Missed Check-Out</span></div></td>"
                        + "<td><span>08:00</span> -&gt; <span>00:00</span></td><td><div><span>Missed Check-In</span></div></td></tr>"
                        + "</table>";

        private com.coc.docs.auth.CurrentUser caller(com.coc.docs.account.AppRole role) {
            return com.coc.docs.auth.TestCallers.user(role);
        }

        private HrmsNotificationService armed() {
            when(repository.findById(9L))
                    .thenReturn(Optional.of(new HrmsNotification(
                            9L, "msg-9", "Attendance on 08-Sep-2026",
                            LocalDate.of(2026, 9, 8), Instant.parse("2026-09-08T05:02:00Z"))));
            when(mailbox.htmlBodyOf("msg-9")).thenReturn(Optional.of(HTML));
            return serviceWith(configured());
        }

        @Test
        @DisplayName("an admin sees the whole department, and no per-caller lookup happens")
        void adminSeesEveryone() {
            var content = armed()
                    .reportFor(9L, caller(com.coc.docs.account.AppRole.ADMIN))
                    .orElseThrow()
                    .content();

            assertThat(content.rows()).hasSize(2);
            verifyNoInteractions(accounts);
            // An admin is never "unlinked": they need no link to read the table.
            var scoped = armed().reportFor(9L, caller(com.coc.docs.account.AppRole.ADMIN)).orElseThrow();
            assertThat(scoped.scopedToViewer()).isFalse();
            assertThat(scoped.viewerLinked()).isTrue();
        }

        @Test
        @DisplayName("a plain user sees only the row their account is linked to")
        void plainUserSeesOnlyTheirOwnRow() {
            var user = caller(com.coc.docs.account.AppRole.USER);
            when(accounts.hrmsNameOf(user.id())).thenReturn(Optional.of("Ken Vantha"));

            var content = armed().reportFor(9L, user).orElseThrow().content();

            assertThat(content.rows()).hasSize(1);
            assertThat(content.rows().get(0).name()).isEqualTo("Ken Vantha");
            assertThat(content.rows().get(0).todayCheckIn().status()).isEqualTo("Missed Check-In");
        }

        @Test
        @DisplayName("matching ignores case and padding, because an admin types what the mail shows")
        void matchingIsCaseAndWhitespaceInsensitive() {
            var user = caller(com.coc.docs.account.AppRole.USER);
            when(accounts.hrmsNameOf(user.id())).thenReturn(Optional.of("  ken   VANTHA "));

            assertThat(armed().reportFor(9L, user).orElseThrow().content().rows()).hasSize(1);
        }

        @Test
        @DisplayName("an unlinked account gets the report with no rows, not somebody else's")
        void unlinkedAccountSeesNoRows() {
            var user = caller(com.coc.docs.account.AppRole.USER);
            when(accounts.hrmsNameOf(user.id())).thenReturn(Optional.empty());

            var content = armed().reportFor(9L, user).orElseThrow().content();

            assertThat(content.rows()).isEmpty();
            // The two flags the modal branches on: scoped, but not linked, so the
            // screen says "your account isn't linked" and never blames HRMS.
            var scoped = armed().reportFor(9L, user).orElseThrow();
            assertThat(scoped.scopedToViewer()).isTrue();
            assertThat(scoped.viewerLinked()).isFalse();
            // Still a report: the screen can say "not linked" rather than "could not read the mail".
            assertThat(content.subject()).isEqualTo("Attendance on 08-Sep-2026");
            assertThat(content.department()).isEqualTo("BeplePay Department");
        }

        @Test
        @DisplayName("a name that matches nobody in the table yields no rows, never a near match")
        void noFuzzyMatching() {
            var user = caller(com.coc.docs.account.AppRole.USER);
            when(accounts.hrmsNameOf(user.id())).thenReturn(Optional.of("Ken"));

            assertThat(armed().reportFor(9L, user).orElseThrow().content().rows()).isEmpty();
        }
    }

    /*
     * Storage (V49, ADR-057). The widening V21 asked to be made on purpose, so
     * these assert the three properties that decision rests on: the body is
     * stored once at arrival, reads come from the table rather than the
     * mailbox, and a report older than V49 still reads.
     * // req: FR-OPS-001, NFR-SEC-PII, ADR-057
     */
    @Nested
    class StoredBody {

        private static final String HTML =
                "<h3><span>COOCON Department</span></h3><table>"
                        + "<tr><th>Name</th><th>Yesterday Check-Out</th><th>Today Check-In</th></tr>"
                        + "<tr><td>Veth Sam Oeun</td>"
                        + "<td><span>17:00</span> -&gt; <span>17:38</span></td><td><div><span>Normal</span></div></td>"
                        + "<td><span>08:00</span> -&gt; <span>08:09</span></td><td><div><span>Late Check-In</span></div></td></tr>"
                        + "</table>";

        private HrmsNotification saved() {
            return new HrmsNotification(
                    9L, "msg-9", "Attendance on 25-Aug-2026",
                    LocalDate.of(2026, 8, 25), Instant.parse("2026-08-25T08:12:00Z"));
        }

        @Test
        @DisplayName("a newly recorded report has its body fetched and stored, once")
        void storesBodyOnFirstRecord() {
            when(mailbox.arrivalsSince(any())).thenReturn(List.of(arrival("msg-9", "Attendance on 25-Aug-2026")));
            when(repository.record(any())).thenReturn(true);
            when(repository.findByMessageId("msg-9")).thenReturn(Optional.of(saved()));
            when(mailbox.htmlBodyOf("msg-9")).thenReturn(Optional.of(HTML));

            assertThat(serviceWith(configured()).poll()).isEqualTo(1);

            ArgumentCaptor<HrmsMailContent> stored = ArgumentCaptor.forClass(HrmsMailContent.class);
            verify(contents).save(org.mockito.ArgumentMatchers.eq(9L), stored.capture());
            assertThat(stored.getValue().department()).isEqualTo("COOCON Department");
            assertThat(stored.getValue().rows()).hasSize(1);
        }

        @Test
        @DisplayName("an already-recorded report is not re-fetched -- the mailbox is opened once per report, not per poll")
        void doesNotRestoreAnAlreadyRecordedReport() {
            when(mailbox.arrivalsSince(any())).thenReturn(List.of(arrival("msg-9", "Attendance on 25-Aug-2026")));
            when(repository.record(any())).thenReturn(false);

            assertThat(serviceWith(configured()).poll()).isZero();

            verify(mailbox, never()).htmlBodyOf(any());
            verifyNoInteractions(contents);
        }

        @Test
        @DisplayName("a report is read from the table, without touching the mailbox")
        void readsFromTheTable() {
            HrmsMailContent fromDb = HrmsReportParser.parse("Attendance on 25-Aug-2026", LocalDate.of(2026, 8, 25), HTML);
            when(repository.findById(9L)).thenReturn(Optional.of(saved()));
            when(contents.findByNotificationId(org.mockito.ArgumentMatchers.eq(9L), any(), any()))
                    .thenReturn(Optional.of(fromDb));

            var content = serviceWith(configured()).report(9L).orElseThrow();

            assertThat(content.rows()).hasSize(1);
            verify(mailbox, never()).htmlBodyOf(any());
        }

        @Test
        @DisplayName("a stored report still reads with the mailbox unconfigured -- which it could not before V49")
        void storedReportReadsWithoutTheMailbox() {
            HrmsMailContent fromDb = HrmsReportParser.parse("Attendance on 25-Aug-2026", LocalDate.of(2026, 8, 25), HTML);
            when(repository.findById(9L)).thenReturn(Optional.of(saved()));
            when(contents.findByNotificationId(org.mockito.ArgumentMatchers.eq(9L), any(), any()))
                    .thenReturn(Optional.of(fromDb));

            assertThat(serviceWith(properties(false, "", "")).report(9L)).isPresent();
        }

        @Test
        @DisplayName("a report recorded before V49 falls back to the mailbox rather than becoming unreadable")
        void fallsBackForPreV49Reports() {
            when(repository.findById(9L)).thenReturn(Optional.of(saved()));
            when(contents.findByNotificationId(org.mockito.ArgumentMatchers.eq(9L), any(), any()))
                    .thenReturn(Optional.empty());
            when(mailbox.htmlBodyOf("msg-9")).thenReturn(Optional.of(HTML));

            var content = serviceWith(configured()).report(9L).orElseThrow();

            assertThat(content.rows()).hasSize(1);
            verify(mailbox).htmlBodyOf("msg-9");
        }

        @Test
        @DisplayName("that fallback stores what it parsed, so the next read needs no mailbox")
        void fallbackBackfillsTheStore() {
            when(repository.findById(9L)).thenReturn(Optional.of(saved()));
            when(contents.findByNotificationId(org.mockito.ArgumentMatchers.eq(9L), any(), any()))
                    .thenReturn(Optional.empty());
            when(mailbox.htmlBodyOf("msg-9")).thenReturn(Optional.of(HTML));

            serviceWith(configured()).report(9L);

            ArgumentCaptor<HrmsMailContent> stored = ArgumentCaptor.forClass(HrmsMailContent.class);
            verify(contents).save(org.mockito.ArgumentMatchers.eq(9L), stored.capture());
            assertThat(stored.getValue().department()).isEqualTo("COOCON Department");
            assertThat(stored.getValue().rows()).hasSize(1);
        }

        @Test
        @DisplayName("a store that fails does not fail the read -- the caller asked for a report, not a write")
        void aFailedBackfillStillServesTheReport() {
            when(repository.findById(9L)).thenReturn(Optional.of(saved()));
            when(contents.findByNotificationId(org.mockito.ArgumentMatchers.eq(9L), any(), any()))
                    .thenReturn(Optional.empty());
            when(mailbox.htmlBodyOf("msg-9")).thenReturn(Optional.of(HTML));
            org.mockito.Mockito.doThrow(new IllegalStateException("disk on fire"))
                    .when(contents)
                    .save(org.mockito.ArgumentMatchers.anyLong(), any());

            assertThat(serviceWith(configured()).report(9L)).isPresent();
        }
    }
}
