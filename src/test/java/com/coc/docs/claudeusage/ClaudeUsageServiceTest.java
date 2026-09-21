package com.coc.docs.claudeusage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coc.docs.account.AppRole;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.auth.TestCallers;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

class ClaudeUsageServiceTest {

    private final ClaudeUsageMemberRepository members = mock(ClaudeUsageMemberRepository.class);
    private final ClaudeUsageDailyRepository daily = mock(ClaudeUsageDailyRepository.class);
    private final AuditRepository audit = mock(AuditRepository.class);
    private final ClaudeUsageService service = new ClaudeUsageService(members, daily, audit, new ObjectMapper());

    private static final CurrentUser ACTOR = TestCallers.user(AppRole.ADMIN);

    private static ClaudeUsageMember member(String email, String status, Long budgetOverride) {
        return new ClaudeUsageMember(
                UUID.randomUUID(), email, email.substring(0, email.indexOf('@')), ClaudeUsageMember.ROLE_MEMBER,
                status, budgetOverride, null, Instant.now(), Instant.now());
    }

    // -------------------------------------------------------------------
    // Roster management
    // -------------------------------------------------------------------

    /*
     * There was an Invite nest here. It went with the endpoint: the roster is
     * filled by recordUsage on a first report, which the ReportUsage tests
     * below still cover, including the promotion of a member left in
     * STATUS_INVITED before the feature was removed.
     */

    @Nested
    class Remove {

        @Test
        @DisplayName("sets status to removed, once")
        void removesMember() {
            UUID id = UUID.randomUUID();
            ClaudeUsageMember active = new ClaudeUsageMember(
                    id, "a@cocdocs.example", "A", ClaudeUsageMember.ROLE_MEMBER, ClaudeUsageMember.STATUS_ACTIVE,
                    null, null, Instant.now(), Instant.now());
            ClaudeUsageMember removed = new ClaudeUsageMember(
                    id, "a@cocdocs.example", "A", ClaudeUsageMember.ROLE_MEMBER, ClaudeUsageMember.STATUS_REMOVED,
                    null, null, Instant.now(), Instant.now());
            when(members.findById(id)).thenReturn(Optional.of(active), Optional.of(removed));

            ClaudeUsageMember result = service.remove(id, ACTOR);

            assertThat(result.status()).isEqualTo(ClaudeUsageMember.STATUS_REMOVED);
            verify(members).updateStatus(id, ClaudeUsageMember.STATUS_REMOVED);
        }

        @Test
        @DisplayName("is a no-op, and does not re-audit, on an already-removed member")
        void removingTwiceIsANoOp() {
            UUID id = UUID.randomUUID();
            when(members.findById(id))
                    .thenReturn(Optional.of(member("gone@cocdocs.example", ClaudeUsageMember.STATUS_REMOVED, null)));

            service.remove(id, ACTOR);

            verify(members, never()).updateStatus(any(), any());
            verify(audit, never()).append(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("404s for a member that does not exist")
        void refusesMissingMember() {
            UUID id = UUID.randomUUID();
            when(members.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.remove(id, ACTOR))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).status())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    class BudgetOverride {

        @Test
        @DisplayName("refuses a zero or negative override")
        void refusesNonPositiveBudget() {
            UUID id = UUID.randomUUID();
            when(members.findById(id)).thenReturn(Optional.of(member("a@cocdocs.example", "active", null)));

            assertThatThrownBy(() -> service.updateBudgetOverride(id, 0L, ACTOR))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).status())
                    .isEqualTo(HttpStatus.BAD_REQUEST);

            verify(members, never()).updateBudgetOverride(any(), any());
        }

        @Test
        @DisplayName("null clears an existing override")
        void nullClearsOverride() {
            UUID id = UUID.randomUUID();
            ClaudeUsageMember withOverride = member("a@cocdocs.example", "active", 2_000_000L);
            when(members.findById(id)).thenReturn(Optional.of(withOverride), Optional.of(member("a@cocdocs.example", "active", null)));

            service.updateBudgetOverride(id, null, ACTOR);

            verify(members).updateBudgetOverride(id, null);
        }
    }

    // -------------------------------------------------------------------
    // Ingestion
    // -------------------------------------------------------------------

    @Nested
    class RecordUsage {

        @Test
        @DisplayName("refuses a future date")
        void refusesFutureDate() {
            assertThatThrownBy(() -> service.recordUsage(
                            "a@cocdocs.example", null, LocalDate.now().plusYears(1), 1, 1, 0, 0, List.of(), Map.of()))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).status())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("refuses negative token counts")
        void refusesNegativeTokens() {
            assertThatThrownBy(() -> service.recordUsage(
                            "a@cocdocs.example", null, LocalDate.now(), -1, 0, 0, 0, List.of(), Map.of()))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).status())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("refuses negative token counts inside byModel too")
        void refusesNegativeByModelTokens() {
            assertThatThrownBy(() -> service.recordUsage(
                            "a@cocdocs.example", null, LocalDate.now(), 10, 10, 0, 0, List.of(),
                            Map.of("claude-sonnet-5", new ClaudeUsageModelTokens(-1, 0, 0, 0))))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).status())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("auto-creates a new member as active, not invited -- reporting IS the opt-in")
        void autoCreatesActiveMember() {
            when(members.findByEmail("new@cocdocs.example")).thenReturn(Optional.empty());
            ClaudeUsageMember created = member("new@cocdocs.example", ClaudeUsageMember.STATUS_ACTIVE, null);
            when(members.insert(eq("new@cocdocs.example"), any(), eq(ClaudeUsageMember.ROLE_MEMBER),
                            eq(ClaudeUsageMember.STATUS_ACTIVE), eq(null)))
                    .thenReturn(created);
            when(daily.upsert(any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any()))
                    .thenReturn(true);

            var outcome = service.recordUsage(
                    "new@cocdocs.example", null, LocalDate.now(), 100, 50, 0, 0, List.of("Sonnet 5"), Map.of());

            assertThat(outcome.action()).isEqualTo(ClaudeUsageService.IngestOutcome.CREATED);
            verify(members, never()).updateStatus(any(), any());
        }

        @Test
        @DisplayName("promotes an invited member to active on their first report")
        void promotesInvitedToActive() {
            UUID id = UUID.randomUUID();
            ClaudeUsageMember invited = new ClaudeUsageMember(
                    id, "inv@cocdocs.example", "Inv", ClaudeUsageMember.ROLE_MEMBER, ClaudeUsageMember.STATUS_INVITED,
                    null, UUID.randomUUID(), Instant.now(), Instant.now());
            when(members.findByEmail("inv@cocdocs.example")).thenReturn(Optional.of(invited));
            when(daily.upsert(any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any()))
                    .thenReturn(true);

            service.recordUsage("inv@cocdocs.example", null, LocalDate.now(), 10, 10, 0, 0, List.of(), Map.of());

            verify(members).updateStatus(id, ClaudeUsageMember.STATUS_ACTIVE);
        }

        @Test
        @DisplayName("never reactivates a removed member on a new report")
        void doesNotReactivateRemovedMember() {
            UUID id = UUID.randomUUID();
            ClaudeUsageMember removed = new ClaudeUsageMember(
                    id, "gone@cocdocs.example", "Gone", ClaudeUsageMember.ROLE_MEMBER, ClaudeUsageMember.STATUS_REMOVED,
                    null, null, Instant.now(), Instant.now());
            when(members.findByEmail("gone@cocdocs.example")).thenReturn(Optional.of(removed));
            when(daily.upsert(any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any()))
                    .thenReturn(false);

            service.recordUsage("gone@cocdocs.example", null, LocalDate.now(), 10, 10, 0, 0, List.of(), Map.of());

            verify(members, never()).updateStatus(any(), any());
        }

        @Test
        @DisplayName("prices real per-model cost server-side, summed across models in the day")
        void pricesPerModelCost() {
            ClaudeUsageMember active = member("a@cocdocs.example", ClaudeUsageMember.STATUS_ACTIVE, null);
            when(members.findByEmail("a@cocdocs.example")).thenReturn(Optional.of(active));
            when(daily.upsert(any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any()))
                    .thenReturn(true);

            // Sonnet 5: $2/M in, $10/M out. Opus 5: $5/M in, $25/M out.
            Map<String, ClaudeUsageModelTokens> byModel = Map.of(
                    "claude-sonnet-5", new ClaudeUsageModelTokens(1_000_000, 0, 0, 0),
                    "claude-opus-5", new ClaudeUsageModelTokens(0, 1_000_000, 0, 0));

            service.recordUsage("a@cocdocs.example", null, LocalDate.now(), 1_000_000, 1_000_000, 0, 0, List.of(), byModel);

            ArgumentCaptor<BigDecimal> cost = ArgumentCaptor.forClass(BigDecimal.class);
            ArgumentCaptor<String> models = ArgumentCaptor.forClass(String.class);
            verify(daily).upsert(any(), any(), eq(1_000_000L), eq(1_000_000L), eq(0L), eq(0L), cost.capture(), models.capture());

            // $2 (1M sonnet input) + $25 (1M opus output) = $27, never the old flat $0.006/1K blended rate.
            assertThat(cost.getValue()).isEqualByComparingTo(new BigDecimal("27.0000"));
            assertThat(models.getValue()).isEqualTo("claude-opus-5, claude-sonnet-5");
        }

        @Test
        @DisplayName("prices $0, not a guess, when no byModel breakdown is sent")
        void pricesZeroWithoutBreakdown() {
            ClaudeUsageMember active = member("a@cocdocs.example", ClaudeUsageMember.STATUS_ACTIVE, null);
            when(members.findByEmail("a@cocdocs.example")).thenReturn(Optional.of(active));
            when(daily.upsert(any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any()))
                    .thenReturn(true);

            service.recordUsage(
                    "a@cocdocs.example", null, LocalDate.now(), 1_000_000, 1_000_000, 0, 0, List.of("Sonnet 5"), Map.of());

            ArgumentCaptor<BigDecimal> cost = ArgumentCaptor.forClass(BigDecimal.class);
            ArgumentCaptor<String> models = ArgumentCaptor.forClass(String.class);
            verify(daily).upsert(any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), cost.capture(), models.capture());

            assertThat(cost.getValue()).isEqualByComparingTo(BigDecimal.ZERO);
            // Falls back to the flat names list when there is no byModel breakdown to derive it from.
            assertThat(models.getValue()).isEqualTo("Sonnet 5");
        }
    }

    // -------------------------------------------------------------------
    // Dashboard rollup
    // -------------------------------------------------------------------

    @Nested
    class Dashboard {

        @Test
        @DisplayName("a day period is exactly one day, capped at today")
        void dayPeriodIsOneDay() {
            LocalDate today = LocalDate.of(2026, 9, 17);
            when(members.list()).thenReturn(List.of());
            when(daily.listInRange(today, today)).thenReturn(List.of());

            var dashboard = service.dashboard("day", 0, today);

            assertThat(dashboard.from()).isEqualTo(today);
            assertThat(dashboard.to()).isEqualTo(today);
        }

        @Test
        @DisplayName("a week period runs Monday to Sunday and is capped at today for the current week")
        void weekPeriodIsMondayToSundayCappedAtToday() {
            LocalDate wednesday = LocalDate.of(2026, 9, 16); // a Wednesday
            when(members.list()).thenReturn(List.of());
            when(daily.listInRange(any(), any())).thenReturn(List.of());

            var dashboard = service.dashboard("week", 0, wednesday);

            assertThat(dashboard.from()).isEqualTo(LocalDate.of(2026, 9, 14)); // Monday
            assertThat(dashboard.to()).isEqualTo(wednesday); // capped, not Sunday the 20th
        }

        @Test
        @DisplayName("an unrecognised period falls back to week rather than erroring")
        void unknownPeriodFallsBackToWeek() {
            LocalDate wednesday = LocalDate.of(2026, 9, 16);
            when(members.list()).thenReturn(List.of());
            when(daily.listInRange(any(), any())).thenReturn(List.of());

            var dashboard = service.dashboard("fortnight", 0, wednesday);

            assertThat(dashboard.period()).isEqualTo("week");
        }

        @Test
        @DisplayName("a month period runs the 1st to the last day of the month")
        void monthPeriodIsCalendarMonth() {
            LocalDate mid = LocalDate.of(2026, 2, 10);
            when(members.list()).thenReturn(List.of());
            when(daily.listInRange(any(), any())).thenReturn(List.of());

            var dashboard = service.dashboard("month", 0, mid);

            assertThat(dashboard.from()).isEqualTo(LocalDate.of(2026, 2, 1));
            assertThat(dashboard.to()).isEqualTo(mid); // capped, not the 28th
        }

        @Test
        @DisplayName("a past month is shown whole, not capped")
        void pastMonthIsShownWhole() {
            LocalDate today = LocalDate.of(2026, 9, 17);
            when(members.list()).thenReturn(List.of());
            when(daily.listInRange(any(), any())).thenReturn(List.of());

            var dashboard = service.dashboard("month", -1, today);

            assertThat(dashboard.from()).isEqualTo(LocalDate.of(2026, 8, 1));
            assertThat(dashboard.to()).isEqualTo(LocalDate.of(2026, 8, 31));
        }

        @Test
        @DisplayName("offset is clamped to -60 rather than walking further into the past")
        void offsetIsClamped() {
            LocalDate today = LocalDate.of(2026, 9, 17);
            when(members.list()).thenReturn(List.of());
            when(daily.listInRange(any(), any())).thenReturn(List.of());

            var farPast = service.dashboard("day", -1000, today);
            var atLimit = service.dashboard("day", -60, today);

            assertThat(farPast.from()).isEqualTo(atLimit.from());
        }

        @Test
        @DisplayName("rolls up tokens/cost per member, sorts by total desc, and flags near-limit at 80%")
        void rollsUpMembersAndFlagsNearLimit() {
            LocalDate day = LocalDate.of(2026, 9, 17);
            ClaudeUsageMember heavy = member("heavy@cocdocs.example", ClaudeUsageMember.STATUS_ACTIVE, 700_000L); // scaled to 100k/day
            ClaudeUsageMember light = member("light@cocdocs.example", ClaudeUsageMember.STATUS_ACTIVE, null);
            when(members.list()).thenReturn(List.of(light, heavy));

            ClaudeUsageDaily heavyRow = new ClaudeUsageDaily(
                    UUID.randomUUID(), heavy.id(), day, 90_000, 10_000, 0, 0, new BigDecimal("0.6000"), "Sonnet 5", Instant.now());
            ClaudeUsageDaily lightRow = new ClaudeUsageDaily(
                    UUID.randomUUID(), light.id(), day, 100, 50, 0, 0, new BigDecimal("0.0009"), "Haiku 4.5", Instant.now());
            when(daily.listInRange(day, day)).thenReturn(List.of(heavyRow, lightRow));

            var dashboard = service.dashboard("day", 0, day);

            assertThat(dashboard.members()).hasSize(2);
            // heavy (100k tokens) sorts before light (150 tokens)
            assertThat(dashboard.members().get(0).member().email()).isEqualTo("heavy@cocdocs.example");
            // 700_000 weekly / 7 * 1 day = 100_000 effective budget; 100_000 total => 100%
            assertThat(dashboard.members().get(0).budgetPct()).isEqualTo(100);
            assertThat(dashboard.members().get(0).modelsUsed()).containsExactly("Sonnet 5");
            assertThat(dashboard.nearLimit()).isEqualTo(1);
            assertThat(dashboard.activeUsers()).isEqualTo(2);
            assertThat(dashboard.totals().totalTokens()).isEqualTo(100_150);
        }

        @Test
        @DisplayName("a removed member never counts toward Near Limit even if over budget")
        void removedMemberNeverCountsTowardNearLimit() {
            LocalDate day = LocalDate.of(2026, 9, 17);
            ClaudeUsageMember removedButOverBudget = member("gone@cocdocs.example", ClaudeUsageMember.STATUS_REMOVED, 100L);
            when(members.list()).thenReturn(List.of(removedButOverBudget));
            ClaudeUsageDaily row = new ClaudeUsageDaily(
                    UUID.randomUUID(), removedButOverBudget.id(), day, 1_000_000, 0, 0, 0, BigDecimal.TEN, "", Instant.now());
            when(daily.listInRange(day, day)).thenReturn(List.of(row));

            var dashboard = service.dashboard("day", 0, day);

            assertThat(dashboard.members().get(0).budgetPct()).isGreaterThan(100);
            assertThat(dashboard.nearLimit()).isZero();
        }
    }

    @Nested
    class EffectiveBudget {

        @Test
        @DisplayName("scales the weekly-equivalent figure by the period length")
        void scalesByDays() {
            assertThat(ClaudeUsageService.effectiveBudgetTokens(700_000L, 7)).isEqualTo(700_000L);
            assertThat(ClaudeUsageService.effectiveBudgetTokens(700_000L, 1)).isEqualTo(100_000L);
            assertThat(ClaudeUsageService.effectiveBudgetTokens(null, 7)).isEqualTo(ClaudeUsageService.DEFAULT_SOFT_BUDGET_TOKENS);
        }
    }
}
