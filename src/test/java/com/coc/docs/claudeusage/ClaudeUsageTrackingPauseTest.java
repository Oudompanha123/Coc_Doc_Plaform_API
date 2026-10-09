package com.coc.docs.claudeusage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coc.docs.account.AppRole;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.auth.TestCallers;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Disabled periods can't be backfilled (ADR-105): once a member is enabled
 * again, a report the CLI kept from while they were disabled is refused for
 * good (409 {@code disabled-period}), while usage from before the disable and
 * from after the resume day is accepted.
 *
 * <p>// req: FR-USAGE-001, FR-USAGE-002, ADR-105
 */
class ClaudeUsageTrackingPauseTest {

    private static final ZoneId KH = ZoneId.of("Asia/Phnom_Penh");
    private static final CurrentUser ADMIN = TestCallers.user(AppRole.SUPER_ADMIN);

    private final ClaudeUsageMemberRepository members = mock(ClaudeUsageMemberRepository.class);
    private final ClaudeUsageDailyRepository daily = mock(ClaudeUsageDailyRepository.class);
    private final InMemoryTrackingPauses pauses = new InMemoryTrackingPauses();
    private final ClaudeUsageService service = new ClaudeUsageService(
            members, daily, mock(ClaudeUsageDailyModelRepository.class), mock(ClaudeUsageActivityRepository.class),
            mock(AuditRepository.class), new ObjectMapper(), pauses);

    private final UUID id = UUID.randomUUID();
    private static final String EMAIL = "pat@cocdocs.example";
    /** The member row as the database would hold it, moved by updateStatus. */
    private final AtomicReference<ClaudeUsageMember> row = new AtomicReference<>(withStatus(ClaudeUsageMember.STATUS_ACTIVE));

    private ClaudeUsageMember withStatus(String status) {
        return new ClaudeUsageMember(id, EMAIL, "Pat", ClaudeUsageMember.ROLE_MEMBER, status, null, null, Instant.now(), Instant.now());
    }

    private void wireMemberRow() {
        when(members.findByEmail(EMAIL)).thenAnswer(inv -> Optional.of(row.get()));
        when(members.findById(id)).thenAnswer(inv -> Optional.of(row.get()));
        doAnswer(inv -> {
            row.set(withStatus(inv.getArgument(1)));
            return null;
        }).when(members).updateStatus(eq(id), any());
        when(daily.upsert(any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any())).thenReturn(true);
    }

    private ClaudeUsageService.IngestOutcome report(LocalDate date) {
        return service.recordUsage(EMAIL, null, date, 10, 10, 0, 0, List.of(), Map.of());
    }

    private static void refusedAs(Throwable e, HttpStatus status, String code) {
        assertThat(e).isInstanceOfSatisfying(ApiException.class, api -> {
            assertThat(api.status()).isEqualTo(status);
            assertThat(api.code()).isEqualTo(code);
        });
    }

    private static Instant at(LocalDate day, int hour) {
        return day.atTime(LocalTime.of(hour, 0)).atZone(KH).toInstant();
    }

    @Test
    @DisplayName("disable -> retry refused (403) -> enable -> disabled-period retry refused (409) -> new data accepted")
    void wholeLifecycle() {
        wireMemberRow();
        LocalDate today = LocalDate.now(KH);
        LocalDate before = today.minusDays(3);

        // Collected before disabling: accepted, and kept throughout.
        report(before);
        verify(daily).upsert(eq(id), eq(before), anyLong(), anyLong(), anyLong(), anyLong(), any(), any());

        service.remove(id, ADMIN);
        assertThat(pauses.listFor(id)).singleElement().satisfies(p -> assertThat(p.resumedAt()).isNull());

        // Retried while disabled: usage and check-in both 403 tracking-disabled.
        assertThatThrownBy(() -> report(today))
                .satisfies(e -> refusedAs(e, HttpStatus.FORBIDDEN, ClaudeUsageService.CODE_TRACKING_DISABLED));
        assertThatThrownBy(() -> service.recordScan(EMAIL))
                .satisfies(e -> refusedAs(e, HttpStatus.FORBIDDEN, ClaudeUsageService.CODE_TRACKING_DISABLED));

        service.restore(id, ADMIN);
        assertThat(pauses.listFor(id)).singleElement().satisfies(p -> assertThat(p.resumedAt()).isNotNull());

        // The CLI's retry of the disabled-period day after Enable: 409, not stored.
        assertThatThrownBy(() -> report(today))
                .satisfies(e -> refusedAs(e, HttpStatus.CONFLICT, ClaudeUsageService.CODE_DISABLED_PERIOD));
        verify(daily, never()).upsert(any(), eq(today), anyLong(), anyLong(), anyLong(), anyLong(), any(), any());

        // From the day after the resume day: accepted. Check-ins are accepted at once.
        LocalDate next = today.plusDays(1);
        report(next);
        verify(daily).upsert(eq(id), eq(next), anyLong(), anyLong(), anyLong(), anyLong(), any(), any());
        assertThat(service.recordScan(EMAIL).action()).isEqualTo(ClaudeUsageService.IngestOutcome.SCAN_RECORDED);

        // The pre-disable day was written once, before disabling, and never again
        // (ClaudeUsageDailyRepository has no delete at all).
        verify(daily).upsert(eq(id), eq(before), anyLong(), anyLong(), anyLong(), anyLong(), any(), any());
    }

    @Nested
    @DisplayName("which days a finished pause refuses")
    class Days {

        private final LocalDate today = LocalDate.now(KH);
        private final LocalDate disableDay = today.minusDays(10);
        private final LocalDate resumeDay = today.minusDays(5);

        private void seedWindow() {
            wireMemberRow();
            pauses.add(id, at(disableDay, 10), at(resumeDay, 15));
        }

        @Test
        @DisplayName("before the disable day: accepted (pre-disable usage is still collectable)")
        void beforeIsAccepted() {
            seedWindow();
            report(disableDay.minusDays(1));
            verify(daily).upsert(eq(id), eq(disableDay.minusDays(1)), anyLong(), anyLong(), anyLong(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("the disable day, every day between, and the resume day: 409 disabled-period")
        void windowIsRefused() {
            seedWindow();
            for (LocalDate d = disableDay; !d.isAfter(resumeDay); d = d.plusDays(1)) {
                LocalDate day = d;
                assertThatThrownBy(() -> report(day))
                        .as("day %s", day)
                        .satisfies(e -> refusedAs(e, HttpStatus.CONFLICT, ClaudeUsageService.CODE_DISABLED_PERIOD));
            }
            verify(daily, never()).upsert(any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("the day after the resume day, and today: accepted")
        void afterIsAccepted() {
            seedWindow();
            report(resumeDay.plusDays(1));
            report(today);
            verify(daily).upsert(eq(id), eq(resumeDay.plusDays(1)), anyLong(), anyLong(), anyLong(), anyLong(), any(), any());
            verify(daily).upsert(eq(id), eq(today), anyLong(), anyLong(), anyLong(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("an older window is still refused after a later disable/enable -- a --days backfill can't reach it")
        void everyWindowCounts() {
            seedWindow();
            pauses.add(id, at(today.minusDays(2), 9), at(today.minusDays(2), 11));
            assertThatThrownBy(() -> report(disableDay.plusDays(1)))
                    .satisfies(e -> refusedAs(e, HttpStatus.CONFLICT, ClaudeUsageService.CODE_DISABLED_PERIOD));
            assertThatThrownBy(() -> report(today.minusDays(2)))
                    .satisfies(e -> refusedAs(e, HttpStatus.CONFLICT, ClaudeUsageService.CODE_DISABLED_PERIOD));
        }

        @Test
        @DisplayName("day boundaries use the team zone, not UTC")
        void teamZoneBoundaries() {
            wireMemberRow();
            // 23:30 UTC is already the next day in Phnom Penh (UTC+7).
            LocalDate khDay = today.minusDays(20);
            Instant lateUtc = khDay.atStartOfDay(KH).plusMinutes(30).toInstant();
            pauses.add(id, lateUtc, lateUtc.plusSeconds(60));
            report(khDay.minusDays(1));
            assertThatThrownBy(() -> report(khDay))
                    .satisfies(e -> refusedAs(e, HttpStatus.CONFLICT, ClaudeUsageService.CODE_DISABLED_PERIOD));
        }
    }

    @Test
    @DisplayName("disabling twice keeps the first start; enabling twice does not move the resume")
    void idempotentEdges() {
        wireMemberRow();
        service.remove(id, ADMIN);
        Instant firstStart = pauses.listFor(id).get(0).disabledAt();
        service.remove(id, ADMIN); // already removed: a no-op
        assertThat(pauses.listFor(id)).singleElement().satisfies(p -> assertThat(p.disabledAt()).isEqualTo(firstStart));

        service.restore(id, ADMIN);
        Instant resumed = pauses.listFor(id).get(0).resumedAt();
        service.restore(id, ADMIN); // already active: a no-op
        assertThat(pauses.listFor(id)).singleElement().satisfies(p -> assertThat(p.resumedAt()).isEqualTo(resumed));
    }
}
