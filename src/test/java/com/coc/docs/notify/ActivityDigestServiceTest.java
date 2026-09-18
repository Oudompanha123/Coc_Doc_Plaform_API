package com.coc.docs.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import com.coc.docs.audit.AuditAction;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The digest's decisions, with no database and no mail server.
 *
 * <p>These paths — which day, which window, whether to send at all, who to —
 * are otherwise covered only by {@link ActivityDigestIntegrationTest}, which is
 * skipped on any machine without Docker. Skipped tests prove nothing, so the
 * decisions that do not need Postgres are asserted here, where they always run.
 *
 * <p>// req: FR-OPS-001, NFR-OPS-AUDIT
 */
class ActivityDigestServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 14);

    private final ActivityDigestRepository repository = mock(ActivityDigestRepository.class);
    private final NotificationMailService mail = mock(NotificationMailService.class);
    private final AuditRepository audit = mock(AuditRepository.class);
    private final ObjectMapper json = new ObjectMapper();

    private ActivityDigestService serviceWith(NotificationProperties properties, Clock clock) {
        return new ActivityDigestService(
                repository, mail, new ReportMailRenderer(properties), properties, audit, json, clock);
    }

    private ActivityDigestService service() {
        return serviceWith(
                new NotificationProperties(null, null, null, false),
                Clock.fixed(Instant.parse("2026-09-15T01:00:00Z"), ZoneOffset.UTC));
    }

    private static PageActivity activity(String section, String slug, String title) {
        return new PageActivity(
                section, slug, title, Instant.parse("2026-09-14T02:12:00Z"), "Sok Dara",
                "dara@example.com", AuditAction.PAGE_PUBLISH, 1, false, false, true);
    }

    private void givenActivity(PageActivity... rows) {
        when(repository.pageActivityBetween(any(), any())).thenReturn(List.of(rows));
    }

    private static CurrentUser caller() {
        Account account = new Account(
                UUID.randomUUID(),
                "boss@example.com",
                "Boss",
                EnumSet.of(AppRole.SUPER_ADMIN),
                null,
                null,
                AccountStatus.ACTIVE,
                null,
                Instant.now(),
                Instant.now(),
                false,
                // hasPassword — the twelfth component, added with ADR-031.
                // This caller is a fixture for the digest; it holds no
                // password and does not need one.
                false);
        return new CurrentUser(account, Set.of(), Set.of(), new byte[] {1});
    }

    @Nested
    @DisplayName("the day and the window")
    class Window {

        @Test
        @DisplayName("yesterday is yesterday in the configured zone, not in UTC")
        void yesterdayUsesConfiguredZone() {
            // 18:00 UTC on the 14th is already 01:00 on the 15th in Phnom Penh
            // (UTC+7), so "yesterday" there is the 14th — while a UTC reading
            // would say the 13th and silently skip a day.
            ActivityDigestService service = serviceWith(
                    new NotificationProperties(null, "Asia/Phnom_Penh", null, false),
                    Clock.fixed(Instant.parse("2026-09-14T18:00:00Z"), ZoneOffset.UTC));

            assertThat(service.yesterday()).isEqualTo(LocalDate.of(2026, 9, 14));
        }

        @Test
        @DisplayName("the window is that day's midnight-to-midnight in the configured zone")
        void windowIsLocalMidnightToMidnight() {
            givenActivity();
            service().sendDigestFor(DAY);

            ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
            ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
            verify(repository).pageActivityBetween(from.capture(), to.capture());

            // Phnom Penh is UTC+7, so the local day starts at 17:00 UTC the day before.
            assertThat(from.getValue()).isEqualTo(Instant.parse("2026-09-13T17:00:00Z"));
            assertThat(to.getValue()).isEqualTo(Instant.parse("2026-09-14T17:00:00Z"));
        }
    }

    @Nested
    @DisplayName("whether to send")
    class Sending {

        @Test
        @DisplayName("a day with no activity is not sent, and says why")
        void skipsEmptyDay() {
            givenActivity();

            ActivityDigestService.DigestResult result = service().sendDigestFor(DAY);

            assertThat(result.sent()).isFalse();
            assertThat(result.outcome()).isEqualTo(ActivityDigestService.Outcome.NO_ACTIVITY);
            assertThat(result.pagesChanged()).isZero();
            verify(mail, never()).sendBcc(any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("an empty day is sent when the deployment asked for that")
        void sendsEmptyDayWhenConfigured() {
            givenActivity();
            when(repository.adminRecipients()).thenReturn(List.of("boss@example.com"));
            when(mail.sendBcc(any(), anyString(), anyString(), anyString())).thenReturn(true);

            ActivityDigestService service = serviceWith(
                    new NotificationProperties(null, null, null, true),
                    Clock.fixed(Instant.parse("2026-09-15T01:00:00Z"), ZoneOffset.UTC));

            assertThat(service.sendDigestFor(DAY).sent()).isTrue();
        }

        @Test
        @DisplayName("the subject names the brand and the day")
        void subjectNamesTheDay() {
            givenActivity(activity("jex2.0", "getting-started", "Getting Started"));
            when(repository.adminRecipients()).thenReturn(List.of("boss@example.com"));

            service().sendDigestFor(DAY);

            ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
            verify(mail).sendBcc(any(), subject.capture(), anyString(), anyString());
            assertThat(subject.getValue()).isEqualTo("Coc Docs Platform — content activity on 14-Sep-2026");
        }

        @Test
        @DisplayName("the result reports what went out")
        void resultCountsWhatWasSent() {
            givenActivity(
                    activity("jex2.0", "a", "A"),
                    activity("jex2.0", "b", "B"),
                    activity("guides", "c", "C"));
            when(repository.adminRecipients()).thenReturn(List.of("one@example.com", "two@example.com"));
            when(mail.sendBcc(any(), anyString(), anyString(), anyString())).thenReturn(true);

            ActivityDigestService.DigestResult result = service().sendDigestFor(DAY);

            assertThat(result.day()).isEqualTo(DAY);
            assertThat(result.sent()).isTrue();
            assertThat(result.pagesChanged()).isEqualTo(3);
            assertThat(result.sections()).isEqualTo(2);
            assertThat(result.recipients()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("recipients")
    class Recipients {

        @Test
        @DisplayName("configured addresses are used as given")
        void prefersConfiguredRecipients() {
            givenActivity(activity("jex2.0", "a", "A"));

            ActivityDigestService service = serviceWith(
                    new NotificationProperties(null, null, List.of("pm@example.com"), false),
                    Clock.fixed(Instant.parse("2026-09-15T01:00:00Z"), ZoneOffset.UTC));
            service.sendDigestFor(DAY);

            verify(mail).sendBcc(eq(List.of("pm@example.com")), anyString(), anyString(), anyString());
            // Not even asked for: a configured list is the answer, and querying
            // anyway would read personal data for nothing.
            verify(repository, never()).adminRecipients();
        }

        @Test
        @DisplayName("with nobody to send to, it says so instead of reporting a failed send")
        void reportsWhenThereIsNobodyToSendTo() {
            givenActivity(activity("jex2.0", "a", "A"));
            when(repository.adminRecipients()).thenReturn(List.of());

            ActivityDigestService.DigestResult result = service().sendDigestFor(DAY);

            assertThat(result.outcome()).isEqualTo(ActivityDigestService.Outcome.NO_RECIPIENTS);
            assertThat(result.sent()).isFalse();
            // The activity is still reported, because it happened — only the
            // delivery did not.
            assertThat(result.pagesChanged()).isEqualTo(1);
            verify(mail, never()).sendBcc(any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("with none configured, the active admins are resolved per send")
        void fallsBackToAdmins() {
            givenActivity(activity("jex2.0", "a", "A"));
            when(repository.adminRecipients()).thenReturn(List.of("admin@example.com"));

            service().sendDigestFor(DAY);

            verify(mail).sendBcc(eq(List.of("admin@example.com")), anyString(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("the audited, on-demand send")
    class OnDemand {

        @Test
        @DisplayName("records who asked, for which day, and what happened")
        void auditsTheRequest() {
            givenActivity(activity("jex2.0", "a", "A"));
            when(repository.adminRecipients()).thenReturn(List.of("admin@example.com"));
            when(mail.sendBcc(any(), anyString(), anyString(), anyString())).thenReturn(true);

            CurrentUser boss = caller();
            service().sendDigestFor(DAY, boss);

            ArgumentCaptor<JsonNode> detail = ArgumentCaptor.forClass(JsonNode.class);
            verify(audit).append(
                    eq(boss.id()),
                    eq("boss@example.com"),
                    eq(AuditAction.NOTIFICATION_DIGEST_SEND),
                    eq(AuditAction.TARGET_NOTIFICATION),
                    eq(null),
                    detail.capture());

            assertThat(detail.getValue().get("day").asText()).isEqualTo("2026-09-14");
            assertThat(detail.getValue().get("sent").asBoolean()).isTrue();
            assertThat(detail.getValue().get("pagesChanged").asInt()).isEqualTo(1);
        }

        @Test
        @DisplayName("records the attempt even when nothing was sent")
        void auditsUnsentRequests() {
            givenActivity();

            service().sendDigestFor(DAY, caller());

            ArgumentCaptor<JsonNode> detail = ArgumentCaptor.forClass(JsonNode.class);
            verify(audit).append(any(), anyString(), anyString(), anyString(), eq(null), detail.capture());
            assertThat(detail.getValue().get("sent").asBoolean()).isFalse();
        }
    }
}
