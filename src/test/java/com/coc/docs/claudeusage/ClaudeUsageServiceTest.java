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
    private final ClaudeUsageDailyModelRepository dailyModels = mock(ClaudeUsageDailyModelRepository.class);
    private final ClaudeUsageActivityRepository activities = mock(ClaudeUsageActivityRepository.class);
    private final AuditRepository audit = mock(AuditRepository.class);
    private final ClaudeUsageService service =
            new ClaudeUsageService(members, daily, dailyModels, activities, audit, new ObjectMapper());

    private static final CurrentUser ACTOR = TestCallers.user(AppRole.ADMIN);

    private static ClaudeUsageMember member(String email, String status, Long budgetOverride) {
        return new ClaudeUsageMember(
                UUID.randomUUID(), email, email.substring(0, email.indexOf('@')), ClaudeUsageMember.ROLE_MEMBER,
                status, budgetOverride, null, Instant.now(), Instant.now());
    }

    // -------------------------------------------------------------------
    // Roster management
    // -------------------------------------------------------------------

    @Nested
    class Invite {

        @Test
        @DisplayName("refuses to invite an email that is already tracked")
        void refusesDuplicateEmail() {
            when(members.findByEmail("dev@cocdocs.example"))
                    .thenReturn(Optional.of(member("dev@cocdocs.example", ClaudeUsageMember.STATUS_ACTIVE, null)));

            assertThatThrownBy(() -> service.invite("Dev@CocDocs.example", null, null, ACTOR))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).status())
                    .isEqualTo(HttpStatus.CONFLICT);

            verify(members, never()).insert(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("refuses an unknown teamRole")
        void refusesUnknownTeamRole() {
            when(members.findByEmail(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.invite("new@cocdocs.example", null, "owner", ACTOR))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).status())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("lower-cases the email, defaults the role to member, and falls back to the local part for the name")
        void createsInvitedMember() {
            when(members.findByEmail("new@cocdocs.example")).thenReturn(Optional.empty());
            when(members.insert(eq("new@cocdocs.example"), eq("new"), eq(ClaudeUsageMember.ROLE_MEMBER),
                            eq(ClaudeUsageMember.STATUS_INVITED), eq(ACTOR.id())))
                    .thenReturn(member("new@cocdocs.example", ClaudeUsageMember.STATUS_INVITED, null));

            ClaudeUsageMember created = service.invite("New@CocDocs.example", "  ", null, ACTOR);

            assertThat(created.status()).isEqualTo(ClaudeUsageMember.STATUS_INVITED);
            verify(audit).append(eq(ACTOR.id()), eq(ACTOR.email()), eq("claude_usage_member.invite"), any(), any(), any());
        }
    }

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

        /*
         * There was a promotesInvitedToActive test here. It went with the
         * status: nothing can put a row into 'invited' any more, V36 deleted
         * the ones that were, and the branch that lifted them is gone. What it
         * was really asserting — that reporting is what makes somebody active —
         * is covered by autoCreatesActiveMember above, which is the only path
         * into this table now.
         */

        @Test
        @DisplayName("an existing active member's status is left alone")
        void leavesActiveStatusAlone() {
            // The other half of "status only moves in the direction an admin
            // chose": a report must not write a status at all for a row that
            // already has one.
            UUID id = UUID.randomUUID();
            ClaudeUsageMember active = new ClaudeUsageMember(
                    id, "dev@cocdocs.example", "Dev", ClaudeUsageMember.ROLE_MEMBER, ClaudeUsageMember.STATUS_ACTIVE,
                    null, null, Instant.now(), Instant.now());
            when(members.findByEmail("dev@cocdocs.example")).thenReturn(Optional.of(active));
            when(daily.upsert(any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any()))
                    .thenReturn(true);

            service.recordUsage("dev@cocdocs.example", null, LocalDate.now(), 10, 10, 0, 0, List.of(), Map.of());

            verify(members, never()).updateStatus(any(), any());
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

    /**
     * Who the dashboard is about, decided from the session.
     *
     * <p>A plain user reaches this screen to read their own usage; what a
     * colleague costs is not something this product tells them. The scope is
     * applied to the roster and the daily rows before the rollup, so these
     * assert on the derived figures too — a filter that narrowed the member
     * table while leaving "active users" counting the whole team would pass a
     * test that only looked at members.
     */
    @Nested
    @DisplayName("scope")
    class Scope {

        private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

        private ClaudeUsageDaily row(ClaudeUsageMember owner, long tokens) {
            return new ClaudeUsageDaily(
                    UUID.randomUUID(), owner.id(), DAY, tokens, tokens, 0, 0,
                    BigDecimal.ONE, "Opus 5", Instant.now());
        }

        @Test
        @DisplayName("a user sees only their own row, and every figure agrees")
        void userSeesOnlyThemselves() {
            ClaudeUsageMember mine = member("me@cocdocs.example", ClaudeUsageMember.STATUS_ACTIVE, null);
            ClaudeUsageMember theirs = member("you@cocdocs.example", ClaudeUsageMember.STATUS_ACTIVE, null);
            when(members.list()).thenReturn(List.of(mine, theirs));
            when(daily.listInRange(DAY, DAY)).thenReturn(List.of(row(mine, 10), row(theirs, 999)));

            var dashboard = service.dashboard("day", 0, DAY, "me@cocdocs.example");

            assertThat(dashboard.members()).hasSize(1);
            assertThat(dashboard.members().get(0).member().email()).isEqualTo("me@cocdocs.example");
            // The derived figures, which a members-only assertion would miss.
            assertThat(dashboard.activeUsers()).isEqualTo(1);
            assertThat(dashboard.totals().totalTokens()).isEqualTo(20);
        }

        @Test
        @DisplayName("the email is normalised, so a differently-cased session still matches")
        void matchesRegardlessOfCase() {
            ClaudeUsageMember mine = member("me@cocdocs.example", ClaudeUsageMember.STATUS_ACTIVE, null);
            when(members.list()).thenReturn(List.of(mine));
            when(daily.listInRange(DAY, DAY)).thenReturn(List.of(row(mine, 10)));

            assertThat(service.dashboard("day", 0, DAY, "Me@CocDocs.Example").members()).hasSize(1);
        }

        @Test
        @DisplayName("somebody who has never reported gets an empty dashboard, not an error")
        void neverReportedIsEmpty() {
            when(members.list())
                    .thenReturn(List.of(member("someone@cocdocs.example", ClaudeUsageMember.STATUS_ACTIVE, null)));
            when(daily.listInRange(DAY, DAY)).thenReturn(List.of());

            var dashboard = service.dashboard("day", 0, DAY, "newcomer@cocdocs.example");

            assertThat(dashboard.members()).isEmpty();
            assertThat(dashboard.totals().totalTokens()).isZero();
            assertThat(dashboard.activeUsers()).isZero();
        }

        @Test
        @DisplayName("dashboardFor gives a user only themselves, and an admin the team")
        void dashboardForPicksTheScopeFromTheRole() {
            // The branch the other tests in this nest skip past: they call the
            // filter directly, so a dashboardFor that passed the wrong thing --
            // or null -- would have been invisible to all of them, while the
            // screen showed a plain user the whole team.
            ClaudeUsageMember a = member("a@cocdocs.example", ClaudeUsageMember.STATUS_ACTIVE, null);
            ClaudeUsageMember b = member("b@cocdocs.example", ClaudeUsageMember.STATUS_ACTIVE, null);
            when(members.list()).thenReturn(List.of(a, b));
            when(daily.listInRange(any(), any())).thenReturn(List.of());

            CurrentUser plainUser = TestCallers.user(AppRole.USER);
            assertThat(service.dashboardFor(plainUser, "day", 0).members())
                    .as("a user whose email is on no roster row sees nothing, not everyone")
                    .isEmpty();

            assertThat(service.dashboardFor(TestCallers.user(AppRole.ADMIN), "day", 0).members())
                    .as("an admin still sees the team")
                    .hasSize(2);
        }

        @Test
        @DisplayName("a null scope is the whole team — what an admin gets")
        void nullScopeIsEveryone() {
            when(members.list()).thenReturn(List.of(
                    member("a@cocdocs.example", ClaudeUsageMember.STATUS_ACTIVE, null),
                    member("b@cocdocs.example", ClaudeUsageMember.STATUS_ACTIVE, null)));
            when(daily.listInRange(DAY, DAY)).thenReturn(List.of());

            assertThat(service.dashboard("day", 0, DAY, null).members()).hasSize(2);
        }
    }

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
        @DisplayName("rolls up tokens/cost per member, sorts by total desc, and flags near-limit at 70% (amber)")
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
            assertThat(dashboard.members().get(0).budgetStatus()).isEqualTo(ClaudeUsageMemberUsage.STATUS_RED);
            assertThat(dashboard.members().get(0).modelsUsed()).containsExactly("Sonnet 5");
            assertThat(dashboard.nearLimit()).isEqualTo(1);
            assertThat(dashboard.activeUsers()).isEqualTo(2);
            assertThat(dashboard.totals().totalTokens()).isEqualTo(100_150);
        }

        @Test
        @DisplayName("budget status is green below 70%, amber 70-89%, red at 90%+")
        void budgetStatusThreeTier() {
            LocalDate day = LocalDate.of(2026, 9, 17);
            // 7_000_000 weekly / 7 = 1_000_000/day effective budget for each.
            ClaudeUsageMember green = member("green@cocdocs.example", ClaudeUsageMember.STATUS_ACTIVE, 7_000_000L);
            ClaudeUsageMember amber = member("amber@cocdocs.example", ClaudeUsageMember.STATUS_ACTIVE, 7_000_000L);
            ClaudeUsageMember red = member("red@cocdocs.example", ClaudeUsageMember.STATUS_ACTIVE, 7_000_000L);
            when(members.list()).thenReturn(List.of(green, amber, red));

            ClaudeUsageDaily greenRow = new ClaudeUsageDaily(
                    UUID.randomUUID(), green.id(), day, 600_000, 0, 0, 0, BigDecimal.ZERO, "", Instant.now()); // 60%
            ClaudeUsageDaily amberRow = new ClaudeUsageDaily(
                    UUID.randomUUID(), amber.id(), day, 700_000, 0, 0, 0, BigDecimal.ZERO, "", Instant.now()); // 70%
            ClaudeUsageDaily redRow = new ClaudeUsageDaily(
                    UUID.randomUUID(), red.id(), day, 900_000, 0, 0, 0, BigDecimal.ZERO, "", Instant.now()); // 90%
            when(daily.listInRange(day, day)).thenReturn(List.of(greenRow, amberRow, redRow));

            var dashboard = service.dashboard("day", 0, day);

            var byEmail = dashboard.members().stream()
                    .collect(java.util.stream.Collectors.toMap(u -> u.member().email(), u -> u));
            assertThat(byEmail.get("green@cocdocs.example").budgetStatus()).isEqualTo(ClaudeUsageMemberUsage.STATUS_GREEN);
            assertThat(byEmail.get("amber@cocdocs.example").budgetStatus()).isEqualTo(ClaudeUsageMemberUsage.STATUS_AMBER);
            assertThat(byEmail.get("red@cocdocs.example").budgetStatus()).isEqualTo(ClaudeUsageMemberUsage.STATUS_RED);
            // Near Limit is amber + red together, i.e. at or above 70%.
            assertThat(dashboard.nearLimit()).isEqualTo(2);
        }

        @Test
        @DisplayName("Active Users is period-scoped: a tracked member who reported nothing this period does not count")
        void activeUsersIsPeriodScoped() {
            LocalDate day = LocalDate.of(2026, 9, 17);
            ClaudeUsageMember reportedToday = member("here@cocdocs.example", ClaudeUsageMember.STATUS_ACTIVE, null);
            ClaudeUsageMember quietToday = member("quiet@cocdocs.example", ClaudeUsageMember.STATUS_ACTIVE, null);
            when(members.list()).thenReturn(List.of(reportedToday, quietToday));

            ClaudeUsageDaily row = new ClaudeUsageDaily(
                    UUID.randomUUID(), reportedToday.id(), day, 100, 50, 0, 0, BigDecimal.ZERO, "", Instant.now());
            when(daily.listInRange(day, day)).thenReturn(List.of(row));

            var dashboard = service.dashboard("day", 0, day);

            // Both are STATUS_ACTIVE on the roster, but only one reported tokens today.
            assertThat(dashboard.members()).hasSize(2);
            assertThat(dashboard.activeUsers()).isEqualTo(1);
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
            // Removed is not excluded from Active Users -- that stat is purely
            // period usage, matching the reference tool, which has no roster concept.
            assertThat(dashboard.activeUsers()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("My Claude Usage (ADR-052)")
    class MyDashboard {

        @Test
        @DisplayName("reads the caller's own email from the session, not a parameter")
        void resolvesCallerFromSession() {
            LocalDate today = LocalDate.of(2026, 9, 17);
            CurrentUser actor = TestCallers.user(AppRole.USER);
            when(members.findByEmail(actor.email())).thenReturn(Optional.empty());

            var dashboard = service.myDashboard(actor, today);

            assertThat(dashboard.email()).isEqualTo(actor.email());
            verify(members).findByEmail(actor.email());
            verify(daily, never()).listInRangeForMember(any(), any(), any());
        }

        @Test
        @DisplayName("a caller who has never reported gets an all-zero dashboard, not an error")
        void neverReportedIsZeroNotError() {
            LocalDate today = LocalDate.of(2026, 9, 17);
            CurrentUser actor = TestCallers.user(AppRole.USER);
            when(members.findByEmail(actor.email())).thenReturn(Optional.empty());

            var dashboard = service.myDashboard(actor, today);

            assertThat(dashboard.todayInputTokens()).isZero();
            assertThat(dashboard.todayOutputTokens()).isZero();
            assertThat(dashboard.todayTotalTokens()).isZero();
            assertThat(dashboard.daily()).hasSize(ClaudeUsageService.MY_HISTORY_DAYS);
            assertThat(dashboard.daily()).allMatch(p -> p.totalTokens() == 0);
            assertThat(dashboard.lastUpdated()).isNull();
        }

        @Test
        @DisplayName("today's tokens come from today's row only, not the whole history")
        void todayIsTodaysRowOnly() {
            LocalDate today = LocalDate.of(2026, 9, 17);
            LocalDate yesterday = today.minusDays(1);
            CurrentUser actor = TestCallers.user(AppRole.USER);
            ClaudeUsageMember me = member(actor.email(), ClaudeUsageMember.STATUS_ACTIVE, null);
            when(members.findByEmail(actor.email())).thenReturn(Optional.of(me));

            ClaudeUsageDaily todayRow = new ClaudeUsageDaily(
                    UUID.randomUUID(), me.id(), today, 300, 100, 0, 0, BigDecimal.ZERO, "Sonnet 5", Instant.now());
            ClaudeUsageDaily yesterdayRow = new ClaudeUsageDaily(
                    UUID.randomUUID(), me.id(), yesterday, 9_000, 9_000, 0, 0, BigDecimal.ZERO, "Sonnet 5", Instant.now());
            when(daily.listInRangeForMember(eq(me.id()), any(), eq(today)))
                    .thenReturn(List.of(yesterdayRow, todayRow));

            var dashboard = service.myDashboard(actor, today);

            assertThat(dashboard.todayInputTokens()).isEqualTo(300);
            assertThat(dashboard.todayOutputTokens()).isEqualTo(100);
            assertThat(dashboard.todayTotalTokens()).isEqualTo(400);
        }

        @Test
        @DisplayName("todayUsage rolls up today's row against a one-day budget, and is null off the roster")
        void todayUsageIsOneDayRollup() {
            LocalDate today = LocalDate.of(2026, 9, 17);
            CurrentUser actor = TestCallers.user(AppRole.USER);
            ClaudeUsageMember me = member(actor.email(), ClaudeUsageMember.STATUS_ACTIVE, 700L);
            when(members.findByEmail(actor.email())).thenReturn(Optional.of(me));

            ClaudeUsageDaily todayRow = new ClaudeUsageDaily(
                    UUID.randomUUID(), me.id(), today, 60, 40, 0, 0, new BigDecimal("1.2500"), "Sonnet 5", Instant.now());
            ClaudeUsageDaily yesterdayRow = new ClaudeUsageDaily(
                    UUID.randomUUID(), me.id(), today.minusDays(1), 9_000, 9_000, 0, 0, BigDecimal.TEN, "Opus 5", Instant.now());
            when(daily.listInRangeForMember(eq(me.id()), any(), eq(today)))
                    .thenReturn(List.of(yesterdayRow, todayRow));

            var usage = service.myDashboard(actor, today).todayUsage();

            assertThat(usage.totalTokens()).isEqualTo(100);
            assertThat(usage.costUsd()).isEqualByComparingTo("1.25");
            assertThat(usage.modelsUsed()).containsExactly("Sonnet 5");
            assertThat(usage.effectiveBudgetTokens()).isEqualTo(100);
            assertThat(usage.budgetPct()).isEqualTo(100);
            assertThat(usage.budgetStatus()).isEqualTo(ClaudeUsageMemberUsage.STATUS_RED);

            when(members.findByEmail(actor.email())).thenReturn(Optional.empty());
            assertThat(service.myDashboard(actor, today).todayUsage()).isNull();
        }

        @Test
        @DisplayName("history covers exactly the last 30 days, zero-filled for days with no report")
        void historyIsThirtyDaysZeroFilled() {
            LocalDate today = LocalDate.of(2026, 9, 17);
            CurrentUser actor = TestCallers.user(AppRole.USER);
            ClaudeUsageMember me = member(actor.email(), ClaudeUsageMember.STATUS_ACTIVE, null);
            when(members.findByEmail(actor.email())).thenReturn(Optional.of(me));

            ClaudeUsageDaily onlyRow = new ClaudeUsageDaily(
                    UUID.randomUUID(), me.id(), today, 10, 5, 0, 0, BigDecimal.ZERO, "Sonnet 5", Instant.now());
            when(daily.listInRangeForMember(eq(me.id()), any(), eq(today))).thenReturn(List.of(onlyRow));

            var dashboard = service.myDashboard(actor, today);

            assertThat(dashboard.daily()).hasSize(ClaudeUsageService.MY_HISTORY_DAYS);
            assertThat(dashboard.daily().get(0).date()).isEqualTo(today.minusDays(ClaudeUsageService.MY_HISTORY_DAYS - 1));
            assertThat(dashboard.daily().get(dashboard.daily().size() - 1).date()).isEqualTo(today);
            long daysWithTokens = dashboard.daily().stream().filter(p -> p.totalTokens() > 0).count();
            assertThat(daysWithTokens).isEqualTo(1);
        }

        @Test
        @DisplayName("week sums Monday to today only, against the whole weekly budget (ADR-065)")
        void weekIsMondayToTodayAgainstWholeBudget() {
            LocalDate today = LocalDate.of(2026, 9, 17); // Thursday
            LocalDate monday = LocalDate.of(2026, 9, 14);
            CurrentUser actor = TestCallers.user(AppRole.USER);
            ClaudeUsageMember me = member(actor.email(), ClaudeUsageMember.STATUS_ACTIVE, null);
            when(members.findByEmail(actor.email())).thenReturn(Optional.of(me));

            ClaudeUsageDaily lastSunday = new ClaudeUsageDaily(
                    UUID.randomUUID(), me.id(), monday.minusDays(1), 50_000, 50_000, 0, 0, BigDecimal.ZERO, "Sonnet 5", Instant.now());
            ClaudeUsageDaily mondayRow = new ClaudeUsageDaily(
                    UUID.randomUUID(), me.id(), monday, 200, 306_700, 9_999, 9_999, BigDecimal.ZERO, "Opus 5", Instant.now());
            ClaudeUsageDaily todayRow = new ClaudeUsageDaily(
                    UUID.randomUUID(), me.id(), today, 898, 312_202, 0, 0, BigDecimal.ZERO, "Sonnet 5", Instant.now());
            when(daily.listInRangeForMember(eq(me.id()), any(), eq(today)))
                    .thenReturn(List.of(lastSunday, mondayRow, todayRow));

            var week = service.myDashboard(actor, today).week();

            assertThat(week.from()).isEqualTo(monday);
            assertThat(week.to()).isEqualTo(today);
            // Cache tokens stay out of the token total, the same as todayTotalTokens.
            assertThat(week.totalTokens()).isEqualTo(620_000);
            assertThat(week.budgetTokens()).isEqualTo(ClaudeUsageService.DEFAULT_SOFT_BUDGET_TOKENS);
            assertThat(week.pctUsed()).isEqualTo(52);
            assertThat(week.remainingTokens()).isEqualTo(580_000);
        }

        @Test
        @DisplayName("week uses the member's own weekly override, and never reports negative remaining")
        void weekUsesOverrideAndClampsRemaining() {
            LocalDate today = LocalDate.of(2026, 9, 17);
            CurrentUser actor = TestCallers.user(AppRole.USER);
            ClaudeUsageMember me = member(actor.email(), ClaudeUsageMember.STATUS_ACTIVE, 100_000L);
            when(members.findByEmail(actor.email())).thenReturn(Optional.of(me));
            ClaudeUsageDaily todayRow = new ClaudeUsageDaily(
                    UUID.randomUUID(), me.id(), today, 0, 150_000, 0, 0, BigDecimal.ZERO, "Sonnet 5", Instant.now());
            when(daily.listInRangeForMember(eq(me.id()), any(), eq(today))).thenReturn(List.of(todayRow));

            var week = service.myDashboard(actor, today).week();

            assertThat(week.budgetTokens()).isEqualTo(100_000);
            assertThat(week.pctUsed()).isEqualTo(150);
            assertThat(week.remainingTokens()).isZero();
        }

        @Test
        @DisplayName("subscription is the plan's list price, independent of any usage (ADR-065)")
        void subscriptionIsFixedListPrice() {
            LocalDate today = LocalDate.of(2026, 9, 17);
            CurrentUser actor = TestCallers.user(AppRole.USER);
            when(members.findByEmail(actor.email())).thenReturn(Optional.empty());

            var dashboard = service.myDashboard(actor, today);

            assertThat(dashboard.subscriptionUsdPerMonth()).isEqualByComparingTo("25");
            assertThat(dashboard.week().totalTokens()).isZero();
            assertThat(dashboard.week().budgetTokens()).isEqualTo(ClaudeUsageService.DEFAULT_SOFT_BUDGET_TOKENS);
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

    // -------------------------------------------------------------------
    // Per-model usage (ADR-058)
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("Per-model usage (ADR-058)")
    class PerModelUsage {

        @SuppressWarnings("unchecked")
        private List<ClaudeUsageDailyModel> capturedModelRows() {
            ArgumentCaptor<List<ClaudeUsageDailyModel>> rows = ArgumentCaptor.forClass(List.class);
            verify(dailyModels).replaceDay(any(), any(), rows.capture());
            return rows.getValue();
        }

        @Test
        @DisplayName("keeps each model's four categories separate and prices input and output only")
        void persistsPerModelRows() {
            ClaudeUsageMember active = member("a@cocdocs.example", ClaudeUsageMember.STATUS_ACTIVE, null);
            when(members.findByEmail("a@cocdocs.example")).thenReturn(Optional.of(active));
            LocalDate date = LocalDate.now();

            Map<String, ClaudeUsageModelTokens> byModel = new java.util.LinkedHashMap<>();
            byModel.put("claude-opus-5-5", new ClaudeUsageModelTokens(1_400, 38_300, 5_500_000, 107_900, 107_900));
            byModel.put("claude-haiku-4-5-20251001", new ClaudeUsageModelTokens(1_900, 16, 0, 0, 0));
            // Claude Code's <synthetic> pseudo-model, all zero — never stored.
            byModel.put("<synthetic>", new ClaudeUsageModelTokens(0, 0, 0, 0, 0));

            service.recordUsage(
                    "a@cocdocs.example", null, date, 3_300, 38_316, 5_500_000, 107_900, List.of(), byModel);

            List<ClaudeUsageDailyModel> rows = capturedModelRows();
            assertThat(rows).extracting(ClaudeUsageDailyModel::model)
                    .containsExactly("claude-opus-5-5", "claude-haiku-4-5-20251001");
            ClaudeUsageDailyModel opus = rows.get(0);
            assertThat(opus.inputTokens()).isEqualTo(1_400);
            assertThat(opus.outputTokens()).isEqualTo(38_300);
            assertThat(opus.cacheReadTokens()).isEqualTo(5_500_000);
            assertThat(opus.cacheCreationTokens()).isEqualTo(107_900);
            assertThat(opus.cacheCreation1hTokens()).isEqualTo(107_900);
            assertThat(opus.costUsd()).isEqualByComparingTo("0.7716");
            assertThat(rows.get(1).costUsd()).isEqualByComparingTo("0.00198");

            // The day's own cost is the same pricing, summed then rounded once.
            ArgumentCaptor<BigDecimal> cost = ArgumentCaptor.forClass(BigDecimal.class);
            verify(daily).upsert(any(), eq(date), anyLong(), anyLong(), anyLong(), anyLong(), cost.capture(), any());
            assertThat(cost.getValue()).isEqualByComparingTo("0.7736");
        }

        @Test
        @DisplayName("a replay of the same day replaces that day's model rows, never adds a second set")
        void replayReplacesTheDay() {
            ClaudeUsageMember active = member("a@cocdocs.example", ClaudeUsageMember.STATUS_ACTIVE, null);
            when(members.findByEmail("a@cocdocs.example")).thenReturn(Optional.of(active));
            LocalDate date = LocalDate.now();
            Map<String, ClaudeUsageModelTokens> byModel =
                    Map.of("claude-opus-5-5", new ClaudeUsageModelTokens(10, 10, 0, 0));

            service.recordUsage("a@cocdocs.example", null, date, 10, 10, 0, 0, List.of(), byModel);
            service.recordUsage("a@cocdocs.example", null, date, 10, 10, 0, 0, List.of(), byModel);

            // replaceDay (delete-then-insert for that member+date), twice — never an append.
            verify(dailyModels, times(2)).replaceDay(eq(active.id()), eq(date), any());
        }

        @Test
        @DisplayName("refuses a 1-hour split larger than the cache writes it is part of")
        void refusesOversizedOneHourSplit() {
            assertThatThrownBy(() -> service.recordUsage(
                            "a@cocdocs.example", null, LocalDate.now(), 0, 0, 0, 10, List.of(),
                            Map.of("claude-opus-5-5", new ClaudeUsageModelTokens(0, 0, 0, 10, 11))))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).status())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("the breakdown sums per model over the window, totals equal the models, and compares the window before")
        void breakdownAggregatesByModel() {
            LocalDate today = LocalDate.of(2026, 9, 28);
            CurrentUser actor = TestCallers.user(AppRole.USER);
            ClaudeUsageMember me = member(actor.email(), ClaudeUsageMember.STATUS_ACTIVE, null);
            when(members.findByEmail(actor.email())).thenReturn(Optional.of(me));

            var opusToday = new ClaudeUsageDailyModel(
                    me.id(), today, "claude-opus-5-5", 1_000, 20_000, 4_000_000, 80_000, 80_000, new BigDecimal("2.000000"));
            var opusEarlier = new ClaudeUsageDailyModel(
                    me.id(), today.minusDays(2), "claude-opus-5-5", 400, 18_300, 1_500_000, 27_900, 27_900,
                    new BigDecimal("0.730000"));
            var haiku = new ClaudeUsageDailyModel(
                    me.id(), today.minusDays(1), "claude-haiku-4-5", 1_900, 16, 0, 0, 0, new BigDecimal("0.001980"));
            // Eight days back: the previous 7-day window, not this one.
            var previous = new ClaudeUsageDailyModel(
                    me.id(), today.minusDays(8), "claude-opus-5-5", 1, 1, 0, 0, 0, new BigDecimal("1.500000"));
            when(dailyModels.listInRangeForMember(me.id(), today.minusDays(13), today))
                    .thenReturn(List.of(opusToday, haiku, opusEarlier, previous));
            when(daily.listInRangeForMember(eq(me.id()), any(), eq(today))).thenReturn(List.of());

            var usage = service.myDashboard(actor, today, 7).usage();

            assertThat(usage.rangeDays()).isEqualTo(7);
            assertThat(usage.from()).isEqualTo(today.minusDays(6));
            assertThat(usage.byModel()).extracting(ClaudeUsageMyDashboard.ModelUsage::model)
                    .containsExactly("claude-opus-5-5", "claude-haiku-4-5");
            var opus = usage.byModel().get(0);
            assertThat(opus.inputTokens()).isEqualTo(1_400);
            assertThat(opus.outputTokens()).isEqualTo(38_300);
            assertThat(opus.cacheReadTokens()).isEqualTo(5_500_000);
            assertThat(opus.cacheCreationTokens()).isEqualTo(107_900);
            assertThat(opus.costUsd()).isEqualByComparingTo("2.73");

            assertThat(usage.totals().inputTokens()).isEqualTo(3_300);
            assertThat(usage.totals().cacheReadTokens()).isEqualTo(5_500_000);
            assertThat(usage.totals().costUsd()).isEqualByComparingTo("2.73198");
            assertThat(usage.previousCostUsd()).isEqualByComparingTo("1.5");
            assertThat(usage.recent()).extracting(ClaudeUsageDailyModel::usageDate)
                    .containsExactly(today, today.minusDays(1), today.minusDays(2));
        }

        @Test
        @DisplayName("a day reported before per-model rows existed shows as 'unattributed', so totals still add up")
        void legacyDayIsUnattributed() {
            LocalDate today = LocalDate.of(2026, 9, 28);
            CurrentUser actor = TestCallers.user(AppRole.USER);
            ClaudeUsageMember me = member(actor.email(), ClaudeUsageMember.STATUS_ACTIVE, null);
            when(members.findByEmail(actor.email())).thenReturn(Optional.of(me));

            when(dailyModels.listInRangeForMember(eq(me.id()), any(), eq(today))).thenReturn(List.of());
            ClaudeUsageDaily legacy = new ClaudeUsageDaily(
                    UUID.randomUUID(), me.id(), today.minusDays(1), 100, 50, 7, 3, new BigDecimal("0.4000"), "x", Instant.now());
            when(daily.listInRangeForMember(eq(me.id()), any(), eq(today))).thenReturn(List.of(legacy));

            var usage = service.myDashboard(actor, today, 7).usage();

            assertThat(usage.byModel()).singleElement().satisfies(m -> {
                assertThat(m.model()).isEqualTo(ClaudeUsageService.UNATTRIBUTED_MODEL);
                assertThat(m.cacheReadTokens()).isEqualTo(7);
                assertThat(m.cacheCreationTokens()).isEqualTo(3);
                assertThat(m.costUsd()).isEqualByComparingTo("0.40");
            });
            assertThat(usage.totals().costUsd()).isEqualByComparingTo("0.40");
        }

        @Test
        @DisplayName("an unsupported window falls back to 7 days rather than failing")
        void unsupportedWindowFallsBack() {
            LocalDate today = LocalDate.of(2026, 9, 28);
            CurrentUser actor = TestCallers.user(AppRole.USER);
            when(members.findByEmail(actor.email())).thenReturn(Optional.empty());

            assertThat(service.myDashboard(actor, today, 999).usage().rangeDays()).isEqualTo(7);
            assertThat(service.myDashboard(actor, today, 30).usage().rangeDays()).isEqualTo(30);
        }
    }

    @Nested
    @DisplayName("memberActivity — the admin User Activity detail")
    class MemberActivity {

        private final LocalDate wednesday = LocalDate.of(2026, 9, 16);
        private final LocalDate monday = LocalDate.of(2026, 9, 14);

        @Test
        @DisplayName("covers the dashboard's own period and returns days newest first, split by model")
        void daysNewestFirstWithModels() {
            ClaudeUsageMember m = member("dev@cocdocs.example", ClaudeUsageMember.STATUS_ACTIVE, null);
            when(members.findById(m.id())).thenReturn(Optional.of(m));
            Instant reported = Instant.parse("2026-09-16T09:42:00Z");
            ClaudeUsageDaily mon = new ClaudeUsageDaily(
                    UUID.randomUUID(), m.id(), monday, 100, 40, 0, 0, new BigDecimal("0.10"), "a", reported);
            ClaudeUsageDaily wed = new ClaudeUsageDaily(
                    UUID.randomUUID(), m.id(), wednesday, 300, 200, 10, 5, new BigDecimal("0.50"), "a,b", reported);
            when(daily.listInRangeForMember(m.id(), monday, wednesday)).thenReturn(List.of(mon, wed));
            when(dailyModels.listInRangeForMember(m.id(), monday, wednesday)).thenReturn(List.of(
                    new ClaudeUsageDailyModel(m.id(), wednesday, "opus", 200, 150, 10, 5, 0, new BigDecimal("0.40")),
                    new ClaudeUsageDailyModel(m.id(), wednesday, "haiku", 100, 50, 0, 0, 0, new BigDecimal("0.10"))));

            var activity = service.memberActivity(m.id(), "week", 0, wednesday);

            assertThat(activity.from()).isEqualTo(monday);
            assertThat(activity.to()).isEqualTo(wednesday);
            assertThat(activity.days()).extracting(ClaudeUsageMemberActivity.Day::date).containsExactly(wednesday, monday);
            var first = activity.days().get(0);
            assertThat(first.totalTokens()).isEqualTo(500);
            assertThat(first.reportedAt()).isEqualTo(reported);
            assertThat(first.models()).extracting(ClaudeUsageMemberActivity.ModelTokens::model).containsExactly("opus", "haiku");
        }

        @Test
        @DisplayName("a day with no per-model rows gets one unattributed entry equal to the day")
        void legacyDayIsUnattributed() {
            ClaudeUsageMember m = member("dev@cocdocs.example", ClaudeUsageMember.STATUS_ACTIVE, null);
            when(members.findById(m.id())).thenReturn(Optional.of(m));
            ClaudeUsageDaily legacy = new ClaudeUsageDaily(
                    UUID.randomUUID(), m.id(), monday, 100, 40, 7, 3, new BigDecimal("0.10"), "x", Instant.now());
            when(daily.listInRangeForMember(m.id(), monday, wednesday)).thenReturn(List.of(legacy));
            when(dailyModels.listInRangeForMember(m.id(), monday, wednesday)).thenReturn(List.of());

            var day = service.memberActivity(m.id(), "week", 0, wednesday).days().get(0);

            assertThat(day.models()).singleElement().satisfies(model -> {
                assertThat(model.model()).isEqualTo(ClaudeUsageService.UNATTRIBUTED_MODEL);
                assertThat(model.totalTokens()).isEqualTo(140);
                assertThat(model.cacheReadTokens()).isEqualTo(7);
            });
        }

        @Test
        @DisplayName("skips days that reported no tokens at all")
        void skipsEmptyDays() {
            ClaudeUsageMember m = member("dev@cocdocs.example", ClaudeUsageMember.STATUS_ACTIVE, null);
            when(members.findById(m.id())).thenReturn(Optional.of(m));
            ClaudeUsageDaily empty = new ClaudeUsageDaily(
                    UUID.randomUUID(), m.id(), monday, 0, 0, 0, 0, BigDecimal.ZERO, "", Instant.now());
            when(daily.listInRangeForMember(m.id(), monday, wednesday)).thenReturn(List.of(empty));
            when(dailyModels.listInRangeForMember(m.id(), monday, wednesday)).thenReturn(List.of());

            assertThat(service.memberActivity(m.id(), "week", 0, wednesday).days()).isEmpty();
        }

        @Test
        @DisplayName("an unknown member is a 404")
        void unknownMemberIsNotFound() {
            UUID id = UUID.randomUUID();
            when(members.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.memberActivity(id, "week", 0, wednesday))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).status())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // -------------------------------------------------------------------
    // Activity: requests, sessions, active time, projects (ADR-074)
    // -------------------------------------------------------------------

    private static final Instant T0 = Instant.parse("2026-09-15T02:15:00Z"); // 09:15 Asia/Phnom_Penh

    private static Instant at(int minutes) {
        return T0.plusSeconds(minutes * 60L);
    }

    private static ClaudeUsageDayActivity.Project project(
            String name, int requests, int sessions, long activeSeconds, long input, long output, Instant first, Instant last) {
        return new ClaudeUsageDayActivity.Project(name, requests, requests * 4, 1, sessions, activeSeconds, input, output, 0, 0, first, last);
    }

    private static ClaudeUsageDayActivity.Session session(Instant start, Instant end, int requests, String... projects) {
        return new ClaudeUsageDayActivity.Session(start, end, requests, List.of(projects));
    }

    @Nested
    @DisplayName("ingest — the optional activity object")
    class IngestActivity {

        private final LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Phnom_Penh"));
        private ClaudeUsageMember m;

        private void existingMember() {
            m = member("dev@cocdocs.example", ClaudeUsageMember.STATUS_ACTIVE, null);
            when(members.findByEmail("dev@cocdocs.example")).thenReturn(Optional.of(m));
            when(daily.upsert(any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any())).thenReturn(false);
        }

        private void ingest(long input, long output, ClaudeUsageDayActivity activity) {
            service.recordUsage("dev@cocdocs.example", null, today, input, output, 0, 0, List.of(), Map.of(), activity);
        }

        @Test
        @DisplayName("stores it beside the totals, with project names trimmed")
        void storesActivity() {
            existingMember();
            var activity = new ClaudeUsageDayActivity(3, 12, 1, 1, 1620, List.of(
                    project("  coc-docs-platform ", 3, 1, 1620, 100, 50, at(0), at(27))),
                    List.of(session(at(0), at(27), 3, "coc-docs-platform")));

            ingest(100, 50, activity);

            ArgumentCaptor<ClaudeUsageDayActivity> stored = ArgumentCaptor.forClass(ClaudeUsageDayActivity.class);
            verify(activities).replaceDay(eq(m.id()), eq(today), stored.capture());
            assertThat(stored.getValue().requestCount()).isEqualTo(3);
            assertThat(stored.getValue().projects()).extracting(ClaudeUsageDayActivity.Project::name)
                    .containsExactly("coc-docs-platform");
        }

        @Test
        @DisplayName("an ingest without activity removes the day's stored activity")
        void noActivityClearsDay() {
            existingMember();

            ingest(100, 50, null);

            verify(activities).replaceDay(m.id(), today, null);
        }

        @Test
        @DisplayName("refuses project tokens that add up to more than the day")
        void projectTokensMayNotExceedDay() {
            existingMember();
            var activity = new ClaudeUsageDayActivity(1, 1, 1, 1, 0, List.of(
                    project("a", 1, 1, 0, 80, 50, null, null),
                    project("b", 0, 0, 0, 30, 0, null, null)), List.of());

            assertThatThrownBy(() -> ingest(100, 50, activity))
                    .isInstanceOf(ApiException.class).hasMessageContaining("exceed");
            verify(activities, never()).replaceDay(any(), any(), any());
        }

        @Test
        @DisplayName("refuses a path where a project name belongs")
        void projectIsANameNotAPath() {
            existingMember();
            var activity = new ClaudeUsageDayActivity(1, 1, 1, 1, 0, List.of(
                    project("D:\\CLAUDE\\coc-docs-platform", 1, 1, 0, 1, 1, null, null)), List.of());

            assertThatThrownBy(() -> ingest(100, 50, activity))
                    .isInstanceOf(ApiException.class).hasMessageContaining("not a path");
        }

        @Test
        @DisplayName("refuses a session that ends before it starts")
        void sessionMustNotRunBackwards() {
            existingMember();
            var activity = new ClaudeUsageDayActivity(1, 1, 1, 1, 0, List.of(),
                    List.of(session(at(30), at(10), 1, "a")));

            assertThatThrownBy(() -> ingest(100, 50, activity)).isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("refuses more active time than a day has")
        void activeTimeIsBoundedByTheDay() {
            existingMember();
            var activity = new ClaudeUsageDayActivity(1, 1, 1, 1, 86_401, List.of(), List.of());

            assertThatThrownBy(() -> ingest(100, 50, activity)).isInstanceOf(ApiException.class);
        }
    }

    @Nested
    @DisplayName("memberActivity — requests, sessions, duration and projects")
    class MemberActivityDetail {

        private final LocalDate mon = LocalDate.of(2026, 9, 14);
        private final LocalDate tue = LocalDate.of(2026, 9, 15);
        private final LocalDate wed = LocalDate.of(2026, 9, 16);
        private ClaudeUsageMember m;

        /** Mon: no activity (old reporter). Tue and Wed: two projects each. */
        private void twoDaysOfActivity() {
            m = member("dev@cocdocs.example", ClaudeUsageMember.STATUS_ACTIVE, null);
            when(members.findById(m.id())).thenReturn(Optional.of(m));
            Instant reported = Instant.parse("2026-09-16T09:00:00Z");
            when(daily.listInRangeForMember(m.id(), mon, wed)).thenReturn(List.of(
                    new ClaudeUsageDaily(UUID.randomUUID(), m.id(), mon, 10, 10, 0, 0, new BigDecimal("0.01"), "a", reported),
                    new ClaudeUsageDaily(UUID.randomUUID(), m.id(), tue, 160, 40, 0, 0, new BigDecimal("0.20"), "a", reported),
                    new ClaudeUsageDaily(UUID.randomUUID(), m.id(), wed, 70, 30, 0, 0, new BigDecimal("0.10"), "a", reported)));
            when(dailyModels.listInRangeForMember(m.id(), mon, wed)).thenReturn(List.of());

            Instant tue0915 = Instant.parse("2026-09-15T02:15:00Z");
            Instant wed0900 = Instant.parse("2026-09-16T02:00:00Z");
            Map<LocalDate, ClaudeUsageDayActivity> act = new java.util.TreeMap<>();
            act.put(tue, new ClaudeUsageDayActivity(8, 30, 2, 2, (27 + 26) * 60, List.of(
                            project("coc-docs-platform", 6, 1, 27 * 60, 120, 30, tue0915, tue0915.plusSeconds(27 * 60)),
                            project("ax-harness", 2, 1, 26 * 60, 40, 10, tue0915.plusSeconds(50 * 60), tue0915.plusSeconds(76 * 60))),
                    List.of(
                            session(tue0915, tue0915.plusSeconds(27 * 60), 6, "coc-docs-platform"),
                            session(tue0915.plusSeconds(50 * 60), tue0915.plusSeconds(76 * 60), 2, "ax-harness"))));
            act.put(wed, new ClaudeUsageDayActivity(5, 20, 1, 1, 45 * 60, List.of(
                            project("coc-docs-platform", 4, 1, 45 * 60, 60, 25, wed0900, wed0900.plusSeconds(45 * 60)),
                            project("claude-team-usage", 1, 1, 5 * 60, 10, 5, wed0900.plusSeconds(10 * 60), wed0900.plusSeconds(15 * 60))),
                    List.of(session(wed0900, wed0900.plusSeconds(45 * 60), 5, "claude-team-usage", "coc-docs-platform"))));
            when(activities.listInRangeForMember(m.id(), mon, wed)).thenReturn(act);
        }

        private ClaudeUsageMemberActivity load(String project) {
            return service.memberActivity(m.id(), "week", 0, mon, wed, project, wed);
        }

        @Test
        @DisplayName("summary: requests, sessions and active time summed over the days that reported them")
        void summary() {
            twoDaysOfActivity();

            var a = load(null);

            assertThat(a.summary().requestCount()).isEqualTo(13);
            assertThat(a.summary().sessionCount()).isEqualTo(3);
            assertThat(a.summary().activeSeconds()).isEqualTo((27 + 26 + 45) * 60L);
            assertThat(a.summary().apiCallCount()).isEqualTo(50);
            // Tokens cover every day, including Monday's (no activity).
            assertThat(a.summary().totalTokens()).isEqualTo(20 + 200 + 100);
            assertThat(a.daysWithoutActivity()).isEqualTo(1);
            assertThat(a.days()).extracting(d -> d.activity() == null).containsExactly(false, false, true);
        }

        @Test
        @DisplayName("summary activity is null — not zero — when no day reported activity")
        void summaryWithoutActivityIsNull() {
            m = member("dev@cocdocs.example", ClaudeUsageMember.STATUS_ACTIVE, null);
            when(members.findById(m.id())).thenReturn(Optional.of(m));
            when(daily.listInRangeForMember(m.id(), mon, wed)).thenReturn(List.of(new ClaudeUsageDaily(
                    UUID.randomUUID(), m.id(), mon, 10, 10, 0, 0, BigDecimal.ZERO, "a", Instant.now())));

            var a = load(null);

            assertThat(a.summary().requestCount()).isNull();
            assertThat(a.summary().activeSeconds()).isNull();
            assertThat(a.summary().totalTokens()).isEqualTo(20);
            assertThat(a.projects()).isEmpty();
        }

        @Test
        @DisplayName("projects: summed across days, largest total first, first/last use spanning the range")
        void projectAggregation() {
            twoDaysOfActivity();

            var projects = load(null).projects();

            assertThat(projects).extracting(ClaudeUsageDayActivity.Project::name)
                    .containsExactly("coc-docs-platform", "ax-harness", "claude-team-usage");
            var docs = projects.get(0);
            assertThat(docs.requestCount()).isEqualTo(10);
            assertThat(docs.sessionCount()).isEqualTo(2);
            assertThat(docs.activeSeconds()).isEqualTo((27 + 45) * 60L);
            assertThat(docs.firstAt()).isEqualTo(Instant.parse("2026-09-15T02:15:00Z"));
            assertThat(docs.lastAt()).isEqualTo(Instant.parse("2026-09-16T02:45:00Z"));
        }

        @Test
        @DisplayName("token usage by project: input/output/total summed per project across days")
        void tokensByProject() {
            twoDaysOfActivity();

            var projects = load(null).projects();

            assertThat(projects).extracting(
                            ClaudeUsageDayActivity.Project::name,
                            ClaudeUsageDayActivity.Project::inputTokens,
                            ClaudeUsageDayActivity.Project::outputTokens,
                            ClaudeUsageDayActivity.Project::totalTokens)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("coc-docs-platform", 180L, 55L, 235L),
                            org.assertj.core.groups.Tuple.tuple("ax-harness", 40L, 10L, 50L),
                            org.assertj.core.groups.Tuple.tuple("claude-team-usage", 10L, 5L, 15L));
        }

        @Test
        @DisplayName("sessions: every activity session in range, newest first, with its day")
        void sessionsNewestFirst() {
            twoDaysOfActivity();

            var sessions = load(null).sessions();

            assertThat(sessions).extracting(ClaudeUsageMemberActivity.Session::date).containsExactly(wed, tue, tue);
            assertThat(sessions.get(0).durationSeconds()).isEqualTo(45 * 60L);
            assertThat(sessions.get(0).projects()).containsExactly("claude-team-usage", "coc-docs-platform");
        }

        @Test
        @DisplayName("a project filter narrows days, tokens, sessions and summary to that project; cost is not guessed")
        void projectFilter() {
            twoDaysOfActivity();

            var a = load("ax-harness");

            assertThat(a.project()).isEqualTo("ax-harness");
            assertThat(a.days()).singleElement().satisfies(day -> {
                assertThat(day.date()).isEqualTo(tue);
                assertThat(day.totalTokens()).isEqualTo(50);
                assertThat(day.costUsd()).isNull();
                assertThat(day.models()).isEmpty();
            });
            assertThat(a.summary().requestCount()).isEqualTo(2);
            assertThat(a.summary().activeSeconds()).isEqualTo(26 * 60L);
            assertThat(a.sessions()).singleElement().satisfies(s -> assertThat(s.projects()).containsExactly("ax-harness"));
            assertThat(a.projects()).extracting(ClaudeUsageDayActivity.Project::name).containsExactly("ax-harness");
        }

        @Test
        @DisplayName("custom range: from and to together, never reversed, capped at today, at most a year")
        void customRangeValidation() {
            m = member("dev@cocdocs.example", ClaudeUsageMember.STATUS_ACTIVE, null);
            when(members.findById(m.id())).thenReturn(Optional.of(m));

            assertThatThrownBy(() -> service.memberActivity(m.id(), "week", 0, mon, null, null, wed))
                    .isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> service.memberActivity(m.id(), "week", 0, wed, mon, null, wed))
                    .isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> service.memberActivity(m.id(), "week", 0, wed.minusDays(400), wed, null, wed))
                    .isInstanceOf(ApiException.class);

            var capped = service.memberActivity(m.id(), "week", 0, mon, wed.plusDays(10), null, wed);
            assertThat(capped.from()).isEqualTo(mon);
            assertThat(capped.to()).isEqualTo(wed);
        }
    }
}
