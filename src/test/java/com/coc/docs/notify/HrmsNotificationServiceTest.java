package com.coc.docs.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.coc.docs.account.AccountRepository;
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

    /** Only the poll path uses it; the read tests leave it unstubbed. */
    private final AttendanceMailService attendanceMail = mock(AttendanceMailService.class);

    /**
     * Unstubbed by default, so no account has a credential and no personal
     * inbox is ever opened (ADR-077). That is the right default for every test
     * here except the ones that opt in: polling somebody's mailbox should take
     * a deliberate act to arrange, in tests as in production.
     */
    private final AccountMailCredentialRepository mailCredentials = mock(AccountMailCredentialRepository.class);

    /** Unstubbed: App Password credentials never reach it (ADR-085). */
    private final GoogleMailOAuthService oauth = mock(GoogleMailOAuthService.class);

    private HrmsNotificationService serviceWith(HrmsMailProperties props) {
        return new HrmsNotificationService(
                mailbox, repository, props, accounts, contents, attendanceMail, mailCredentials, oauth);
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
            when(repository.record(any(), any())).thenReturn(true);

            assertThat(serviceWith(configured()).poll()).isEqualTo(1);

            ArgumentCaptor<HrmsNotification> written = ArgumentCaptor.forClass(HrmsNotification.class);
            verify(repository).record(written.capture(), isNull());
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
            when(repository.record(any(), any())).thenReturn(false);

            assertThat(serviceWith(configured()).poll()).isZero();
        }

        @Test
        @DisplayName("stores a null report date rather than dropping an unparseable subject")
        void unparseableSubjectStillRecorded() {
            when(mailbox.arrivalsSince(any())).thenReturn(List.of(arrival("<b@hrms>", "Attendance summary")));
            when(repository.record(any(), any())).thenReturn(true);

            assertThat(serviceWith(configured()).poll()).isEqualTo(1);

            ArgumentCaptor<HrmsNotification> written = ArgumentCaptor.forClass(HrmsNotification.class);
            verify(repository).record(written.capture(), isNull());
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
            when(repository.latestReceivedAt(null)).thenReturn(Optional.of(latest));

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
            when(repository.latestReceivedAt(null)).thenReturn(Optional.of(longAgo));

            assertThat(serviceWith(configured()).searchFrom()).isBefore(Instant.parse("2026-08-01T00:00:00Z"));
        }

        @Test
        @DisplayName("falls back to the window before now on an empty table")
        void emptyTableLooksBackFromNow() {
            when(repository.latestReceivedAt(null)).thenReturn(Optional.empty());

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
            // any() for the viewer: these assert which ROWS a role sees inside
            // a report it is already allowed to open. Whether it is allowed to
            // open it at all is owner scoping, which OwnerScoping covers and
            // the database enforces -- pinning a viewer here would couple this
            // nest to a UUID TestCallers makes up per call.
            when(repository.findById(eq(9L), any()))
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
            when(accounts.hrmsNameOf(user.id())).thenReturn(Optional.of("Ken Vantha"));  // explicit override still wins

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
            when(repository.record(any(), any())).thenReturn(true);
            when(repository.findByMessageId("msg-9", null)).thenReturn(Optional.of(saved()));
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
            when(repository.record(any(), any())).thenReturn(false);

            assertThat(serviceWith(configured()).poll()).isZero();

            verify(mailbox, never()).htmlBodyOf(any());
            verifyNoInteractions(contents);
        }

        @Test
        @DisplayName("a report is read from the table, without touching the mailbox")
        void readsFromTheTable() {
            HrmsMailContent fromDb = HrmsReportParser.parse("Attendance on 25-Aug-2026", LocalDate.of(2026, 8, 25), HTML);
            when(repository.findById(9L, null)).thenReturn(Optional.of(saved()));
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
            when(repository.findById(9L, null)).thenReturn(Optional.of(saved()));
            when(contents.findByNotificationId(org.mockito.ArgumentMatchers.eq(9L), any(), any()))
                    .thenReturn(Optional.of(fromDb));

            assertThat(serviceWith(properties(false, "", "")).report(9L)).isPresent();
        }

        @Test
        @DisplayName("a report recorded before V49 falls back to the mailbox rather than becoming unreadable")
        void fallsBackForPreV49Reports() {
            when(repository.findById(9L, null)).thenReturn(Optional.of(saved()));
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
            when(repository.findById(9L, null)).thenReturn(Optional.of(saved()));
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
            when(repository.findById(9L, null)).thenReturn(Optional.of(saved()));
            when(contents.findByNotificationId(org.mockito.ArgumentMatchers.eq(9L), any(), any()))
                    .thenReturn(Optional.empty());
            when(mailbox.htmlBodyOf("msg-9")).thenReturn(Optional.of(HTML));
            org.mockito.Mockito.doThrow(new IllegalStateException("disk on fire"))
                    .when(contents)
                    .save(org.mockito.ArgumentMatchers.anyLong(), any());

            assertThat(serviceWith(configured()).report(9L)).isPresent();
        }
    }

    /*
     * ReportsForDate backs the admin Attendance page (unscoped — that route
     * sits at the admin floor, not the per-caller one ReportScope covers
     * above), and reuses StoredBody's own stored-first/mailbox-fallback path
     * per arrival rather than a separate rule.
     */
    @Nested
    @DisplayName("every department's report for a date")
    class ReportsForDate {

        private static final LocalDate DAY = LocalDate.of(2026, 9, 7);

        private static HrmsNotification notification(long id, String messageId) {
            return new HrmsNotification(
                    id, messageId, "Attendance on 07-Sep-2026", DAY, Instant.parse("2026-09-07T05:02:00Z"));
        }

        @Test
        @DisplayName("never opens a mailbox while disabled")
        void disabledFetchesNothing() {
            when(repository.byReportDate(DAY, null)).thenReturn(List.of(notification(1, "<a@hrms>")));

            assertThat(serviceWith(properties(false, "someone@gmail.com", "hrms@example.com")).reportsForDate(DAY))
                    .isEmpty();
            verifyNoInteractions(mailbox);
        }

        @Test
        @DisplayName("fetches one body per arrival recorded for that date")
        void fetchesEveryArrival() {
            when(repository.byReportDate(DAY, null)).thenReturn(List.of(notification(1, "<a@hrms>"), notification(2, "<b@hrms>")));
            when(mailbox.htmlBodyOf("<a@hrms>")).thenReturn(Optional.of("<html><body>COOCON</body></html>"));
            when(mailbox.htmlBodyOf("<b@hrms>")).thenReturn(Optional.of("<html><body>Engineering</body></html>"));

            assertThat(serviceWith(configured()).reportsForDate(DAY)).hasSize(2);
        }

        /**
         * A body that has gone from the mailbox between polling and viewing is
         * left out, not treated as a failure of the whole date — the same
         * "empty means not available now" contract {@link #report} already
         * has, applied per-item instead of to the one result.
         */
        @Test
        @DisplayName("silently drops an arrival whose body is no longer available")
        void dropsUnavailableBody() {
            when(repository.byReportDate(DAY, null)).thenReturn(List.of(notification(1, "<a@hrms>"), notification(2, "<b@hrms>")));
            when(mailbox.htmlBodyOf("<a@hrms>")).thenReturn(Optional.of("<html><body>COOCON</body></html>"));
            when(mailbox.htmlBodyOf("<b@hrms>")).thenReturn(Optional.empty());

            assertThat(serviceWith(configured()).reportsForDate(DAY)).hasSize(1);
        }

        @Test
        @DisplayName("no arrivals for the date is an empty list, not an error")
        void noArrivalsIsEmpty() {
            when(repository.byReportDate(DAY, null)).thenReturn(List.of());

            assertThat(serviceWith(configured()).reportsForDate(DAY)).isEmpty();
        }
    }

    /**
     * The caller's own row for one date, across whichever department actually
     * names them (self-service, ADR-084). Backs {@code GET /api/v1/me/attendance}.
     */
    @Nested
    @DisplayName("the caller's own attendance for a date")
    class MyAttendance {

        private static final LocalDate DAY = LocalDate.of(2026, 9, 7);

        private static final String BEPLEPAY_HTML =
                "<h3><span>BeplePay Department</span></h3><table>"
                        + "<tr><th>Name</th><th>Yesterday Check-Out</th><th>Today Check-In</th></tr>"
                        + "<tr><td>Nin Srey Nuth</td>"
                        + "<td><span>17:00</span> -&gt; <span>17:44</span></td><td><div><span>Normal</span></div></td>"
                        + "<td><span>08:00</span> -&gt; <span>08:01</span></td><td><div><span>Late Check-In</span></div></td></tr>"
                        + "</table>";

        private static final String COOCON_HTML =
                "<h3><span>COOCON Department</span></h3><table>"
                        + "<tr><th>Name</th><th>Yesterday Check-Out</th><th>Today Check-In</th></tr>"
                        + "<tr><td>Ken Vantha</td>"
                        + "<td><span>17:00</span> -&gt; <span>00:00</span></td><td><div><span>Missed Check-Out</span></div></td>"
                        + "<td><span>08:00</span> -&gt; <span>00:00</span></td><td><div><span>Missed Check-In</span></div></td></tr>"
                        + "</table>";

        private static HrmsNotification notification(long id, String messageId) {
            return new HrmsNotification(
                    id, messageId, "Attendance on 07-Sep-2026", DAY, Instant.parse("2026-09-07T05:02:00Z"));
        }

        private com.coc.docs.auth.CurrentUser caller() {
            return com.coc.docs.auth.TestCallers.user(com.coc.docs.account.AppRole.USER);
        }

        @Test
        @DisplayName("returns the caller's own row from whichever department actually names them")
        void findsTheirOwnRow() {
            var user = caller();
            when(accounts.hrmsNameOf(user.id())).thenReturn(Optional.of("Ken Vantha"));
            when(repository.byReportDate(DAY, user.id()))
                    .thenReturn(List.of(notification(1, "<a@hrms>"), notification(2, "<b@hrms>")));
            when(mailbox.htmlBodyOf("<a@hrms>")).thenReturn(Optional.of(BEPLEPAY_HTML));
            when(mailbox.htmlBodyOf("<b@hrms>")).thenReturn(Optional.of(COOCON_HTML));

            var scoped = serviceWith(configured()).myAttendanceFor(DAY, user);

            assertThat(scoped.content().department()).isEqualTo("COOCON Department");
            assertThat(scoped.content().rows()).hasSize(1);
            assertThat(scoped.content().rows().get(0).name()).isEqualTo("Ken Vantha");
            assertThat(scoped.scopedToViewer()).isTrue();
            assertThat(scoped.viewerLinked()).isTrue();
        }

        @Test
        @DisplayName("matches by email when no hrms_name is set, same rule as a single report")
        void matchesByEmailWhenNoExplicitName() {
            var user = com.coc.docs.auth.TestCallers.user(com.coc.docs.account.AppRole.USER, "kenvantha@gmail.com");
            when(accounts.hrmsNameOf(user.id())).thenReturn(Optional.empty());
            when(repository.byReportDate(DAY, user.id())).thenReturn(List.of(notification(1, "<b@hrms>")));
            when(mailbox.htmlBodyOf("<b@hrms>")).thenReturn(Optional.of(COOCON_HTML));

            var scoped = serviceWith(configured()).myAttendanceFor(DAY, user);

            assertThat(scoped.content().rows()).hasSize(1);
            assertThat(scoped.viewerLinked()).isTrue();
        }

        @Test
        @DisplayName("linked but absent from every report recorded for the date: empty rows, still linked")
        void linkedButNothingRecordedToday() {
            var user = caller();
            when(accounts.hrmsNameOf(user.id())).thenReturn(Optional.of("Ken Vantha"));
            when(repository.byReportDate(DAY, user.id())).thenReturn(List.of(notification(1, "<a@hrms>")));
            when(mailbox.htmlBodyOf("<a@hrms>")).thenReturn(Optional.of(BEPLEPAY_HTML)); // no "Ken Vantha" row here

            var scoped = serviceWith(configured()).myAttendanceFor(DAY, user);

            assertThat(scoped.content().rows()).isEmpty();
            assertThat(scoped.scopedToViewer()).isTrue();
            assertThat(scoped.viewerLinked()).isTrue();
        }

        @Test
        @DisplayName("no notification recorded for the date at all: empty rows, still linked if hrms_name is set")
        void noNotificationForTheDate() {
            var user = caller();
            when(accounts.hrmsNameOf(user.id())).thenReturn(Optional.of("Ken Vantha"));
            when(repository.byReportDate(DAY, user.id())).thenReturn(List.of());

            var scoped = serviceWith(configured()).myAttendanceFor(DAY, user);

            assertThat(scoped.content().rows()).isEmpty();
            assertThat(scoped.content().reportDate()).isEqualTo(DAY);
            assertThat(scoped.viewerLinked()).isTrue();
        }

        @Test
        @DisplayName("an account with no hrms_name and an email matching nobody is reported as not linked")
        void notLinkedAtAll() {
            var user = com.coc.docs.auth.TestCallers.user(com.coc.docs.account.AppRole.USER, "nobody-here@gmail.com");
            when(accounts.hrmsNameOf(user.id())).thenReturn(Optional.empty());
            when(repository.byReportDate(DAY, user.id())).thenReturn(List.of(notification(1, "<a@hrms>")));
            when(mailbox.htmlBodyOf("<a@hrms>")).thenReturn(Optional.of(BEPLEPAY_HTML));

            var scoped = serviceWith(configured()).myAttendanceFor(DAY, user);

            assertThat(scoped.content().rows()).isEmpty();
            assertThat(scoped.viewerLinked()).isFalse();
        }

        @Test
        @DisplayName("looks up only the notifications this caller may see (ADR-077), never the shared-mailbox-only overload")
        void scopesTheLookupToTheCaller() {
            var user = caller();
            when(accounts.hrmsNameOf(user.id())).thenReturn(Optional.empty());
            when(repository.byReportDate(DAY, user.id())).thenReturn(List.of());

            serviceWith(configured()).myAttendanceFor(DAY, user);

            verify(repository).byReportDate(DAY, user.id());
            verify(repository, never()).byReportDate(DAY);
        }
    }

    /**
     * Reading an account's own inbox (ADR-077).
     *
     * <p>The thing under test is not "does IMAP work" — it is <em>whose</em>
     * mailbox gets opened, and when one gets opened at all. Logging into a
     * colleague's mail is the failure these guard against, so most of them
     * assert something does <b>not</b> happen.
     */
    @Nested
    @DisplayName("personal inboxes")
    class PersonalInboxes {

        private static final java.util.UUID ANA = java.util.UUID.randomUUID();
        private static final java.util.UUID BOREY = java.util.UUID.randomUUID();

        private AccountRepository.MailTarget target(java.util.UUID id, String email) {
            return new AccountRepository.MailTarget(id, email, null);
        }

        /**
         * The default, and the one that must never regress: a platform full of
         * accounts, none of which has handed over a password, opens nobody's
         * mailbox.
         */
        @Test
        @DisplayName("opens no personal mailbox when nobody stored a credential")
        void noCredentialNoLogin() {
            when(mailbox.arrivalsSince(any())).thenReturn(List.of());
            when(accounts.activeMailTargets())
                    .thenReturn(List.of(target(ANA, "ana@example.com"), target(BOREY, "borey@example.com")));
            when(mailCredentials.findFor(any())).thenReturn(Optional.empty());

            serviceWith(configured()).poll();

            verify(mailbox, never()).arrivalsSince(any(), any());
        }

        @Test
        @DisplayName("reads an account's own inbox with that account's own credential")
        void pollsWithOwnCredential() {
            when(mailbox.arrivalsSince(any())).thenReturn(List.of());
            when(accounts.activeMailTargets()).thenReturn(List.of(target(ANA, "ana@example.com")));
            when(mailCredentials.findFor(ANA))
                    .thenReturn(Optional.of(new AccountMailCredentialRepository.Credential(ANA, "ana@gmail.com", "pw", null)));
            when(mailbox.arrivalsSince(any(), any()))
                    .thenReturn(List.of(arrival("<own@hrms>", "Attendance on 08-Sep-2026")));
            when(repository.record(any(), any())).thenReturn(true);

            assertThat(serviceWith(configured()).poll()).isEqualTo(1);

            ArgumentCaptor<HrmsMailbox.Login> used = ArgumentCaptor.forClass(HrmsMailbox.Login.class);
            verify(mailbox).arrivalsSince(any(), used.capture());
            assertThat(used.getValue().username()).isEqualTo("ana@gmail.com");

            // The row is filed under its owner. A null here would put somebody's
            // private mail in the shared feed, where everyone can see it.
            verify(repository).record(any(), eq(ANA));
        }

        /**
         * A revoked App Password is an ordinary event, not an outage. If one
         * account's failure could stop the loop, the person whose credential
         * expired would silently take everybody else's reports down with them.
         */
        @Test
        @DisplayName("one account's failure does not stop the next account")
        void oneFailureDoesNotStopTheRest() {
            when(mailbox.arrivalsSince(any())).thenReturn(List.of());
            when(accounts.activeMailTargets())
                    .thenReturn(List.of(target(ANA, "ana@example.com"), target(BOREY, "borey@example.com")));
            when(mailCredentials.findFor(ANA))
                    .thenReturn(Optional.of(new AccountMailCredentialRepository.Credential(ANA, "ana@gmail.com", "pw", null)));
            when(mailCredentials.findFor(BOREY))
                    .thenReturn(Optional.of(new AccountMailCredentialRepository.Credential(BOREY, "borey@gmail.com", "pw", null)));

            when(mailbox.arrivalsSince(any(), argThat(l -> l != null && l.username().startsWith("ana"))))
                    .thenThrow(new IllegalStateException("boom"));
            when(mailbox.arrivalsSince(any(), argThat(l -> l != null && l.username().startsWith("borey"))))
                    .thenReturn(List.of(arrival("<b@hrms>", "Attendance on 08-Sep-2026")));
            when(repository.record(any(), any())).thenReturn(true);

            assertThat(serviceWith(configured()).poll()).isEqualTo(1);
            verify(repository).record(any(), eq(BOREY));
        }

        /** Each mailbox keeps its own water mark — see {@code LATEST_SQL}. */
        @Test
        @DisplayName("starts each account's search from that account's own newest arrival")
        void searchWindowIsPerMailbox() {
            when(repository.latestReceivedAt(ANA)).thenReturn(Optional.of(Instant.parse("2026-09-08T00:00:00Z")));

            assertThat(serviceWith(configured()).searchFrom(ANA))
                    .isEqualTo(Instant.parse("2026-09-08T00:00:00Z").minus(Duration.ofDays(7)));
        }
    }

    /**
     * Who may read what (ADR-077 §2).
     *
     * <p>These assert that the viewer reaches the query. The refusal itself is
     * enforced in SQL, so {@link HrmsNotificationIntegrationTest} is where it
     * is proved against a real database — but a service that forgets to pass
     * the viewer would make that predicate moot, and that is what these catch.
     */
    @Nested
    @DisplayName("owner scoping")
    class OwnerScoping {

        private static final java.util.UUID VIEWER = java.util.UUID.randomUUID();

        @Test
        @DisplayName("the feed asks only for rows this viewer may see")
        void recentPassesViewer() {
            when(repository.recent(5, VIEWER)).thenReturn(List.of());

            serviceWith(configured()).recent(5, VIEWER);

            verify(repository).recent(5, VIEWER);
        }

        /**
         * The no-session case. Null must mean "the shared mailbox only", never
         * "some default account's mailbox" — an unauthenticated caller
         * acquiring a person's private reports is the worst version of getting
         * this wrong.
         */
        @Test
        @DisplayName("no session sees the shared mailbox only")
        void noSessionIsSharedOnly() {
            when(repository.recent(5, null)).thenReturn(List.of());

            serviceWith(configured()).recent(5);

            verify(repository).recent(5, null);
        }

        /**
         * The Attendance page. It asked for shared rows only until ADR-084,
         * which was invisible while a shared mailbox was mandatory and an
         * empty page the moment one stopped being configured.
         */
        @Test
        @DisplayName("the attendance page asks for the dates this viewer may see")
        void reportsForDatePassesViewer() {
            when(repository.byReportDate(LocalDate.of(2026, 9, 8), VIEWER)).thenReturn(List.of());

            serviceWith(configured()).reportsForDate(LocalDate.of(2026, 9, 8), VIEWER);

            verify(repository).byReportDate(LocalDate.of(2026, 9, 8), VIEWER);
        }

        @Test
        @DisplayName("opening one report asks for it as this viewer")
        void reportPassesViewer() {
            when(repository.findById(42L, VIEWER)).thenReturn(Optional.empty());

            assertThat(serviceWith(configured()).report(42L, VIEWER)).isEmpty();

            verify(repository).findById(42L, VIEWER);
        }
    }

    /**
     * Running with no shared mailbox at all (ADR-084).
     *
     * <p>app.hrms.username and password are blank, which used to mean
     * "misconfigured, do nothing" and now means "every inbox read is
     * somebody's own". What these protect is that a blank shared credential
     * must never be <em>used</em>: connecting with it would log a Google auth
     * failure every thirty minutes and tell nobody anything.
     */
    @Nested
    @DisplayName("no shared mailbox")
    class NoSharedMailbox {

        private static final java.util.UUID ANA = java.util.UUID.randomUUID();

        /** enabled, a sender to filter on, and no shared credential. */
        private HrmsMailProperties personalOnly() {
            return properties(true, "", "noreply-hrms@wecambodia.com");
        }

        @Test
        @DisplayName("is a valid configuration, not an incomplete one")
        void isValidConfiguration() {
            assertThat(personalOnly().active()).isTrue();
            assertThat(personalOnly().sharedInboxUsable()).isFalse();
        }

        /**
         * The regression this guards: poll() must not reach the shared
         * arrivalsSince(Instant) with a blank username and password.
         */
        @Test
        @DisplayName("never opens the shared mailbox with a blank credential")
        void doesNotOpenSharedMailbox() {
            when(accounts.activeMailTargets()).thenReturn(List.of());

            assertThat(serviceWith(personalOnly()).poll()).isZero();

            verify(mailbox, never()).arrivalsSince(any());
        }

        @Test
        @DisplayName("still reads the inbox of an account that stored a credential")
        void stillPollsPersonalInboxes() {
            when(accounts.activeMailTargets())
                    .thenReturn(List.of(new AccountRepository.MailTarget(ANA, "ana@example.com", null)));
            when(mailCredentials.findFor(ANA))
                    .thenReturn(Optional.of(new AccountMailCredentialRepository.Credential(ANA, "ana@gmail.com", "pw", null)));
            when(mailbox.arrivalsSince(any(), any()))
                    .thenReturn(List.of(arrival("<own@hrms>", "Attendance on 08-Sep-2026")));
            when(repository.record(any(), any())).thenReturn(true);

            assertThat(serviceWith(personalOnly()).poll()).isEqualTo(1);

            verify(repository).record(any(), eq(ANA));
            verify(mailbox, never()).arrivalsSince(any());
        }

        /**
         * A blank sender is still fatal, and deliberately so: it is the filter
         * that keeps the search to HRMS mail. Without it a poll would read
         * every message in whatever mailbox it was handed.
         */
        @Test
        @DisplayName("a blank sender switches the whole feature off")
        void blankSenderIsStillFatal() {
            HrmsMailProperties noSender = properties(true, "", "");

            assertThat(noSender.active()).isFalse();
            assertThat(serviceWith(noSender).poll()).isZero();
            verifyNoInteractions(mailbox);
        }
    }
}
