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
