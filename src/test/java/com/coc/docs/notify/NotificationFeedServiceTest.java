package com.coc.docs.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coc.docs.account.AppRole;
import com.coc.docs.audit.AuditAction;
import com.coc.docs.audit.AuditEntry;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.auth.TestCallers;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The scope rule behind the topbar's activity feed.
 *
 * <p>The assertions that matter are the two about which repository method is
 * called. A {@code super_admin} reads every row; every other role reads only
 * rows they were the actor of. Getting that wrong would put one account's email
 * in front of another (NFR-SEC-PII), and it would do so silently, because the
 * feed renders identically either way.
 *
 * <p>// req: FR-OPS-001, FR-AUTH-008, NFR-SEC-PII
 */
class NotificationFeedServiceTest {

    private final AuditRepository audit = mock(AuditRepository.class);
    private final HrmsNotificationService hrms = mock(HrmsNotificationService.class);
    private final NotificationFeedService service = new NotificationFeedService(audit, hrms);

    private static AuditEntry entry(long id, String action, String actorEmail) {
        return entryAt(id, action, actorEmail, Instant.now());
    }

    /** As {@link #entry}, with the moment fixed — for asserting merge order. */
    private static AuditEntry entryAt(long id, String action, String actorEmail, Instant occurredAt) {
        return new AuditEntry(
                id, UUID.randomUUID(), actorEmail, null, action, "account", UUID.randomUUID(), null, occurredAt);
    }

    @Nested
    @DisplayName("scope")
    class Scope {

        @Test
        @DisplayName("a super_admin reads every account's activity")
        void superAdminSeesEveryone() {
            CurrentUser caller = TestCallers.user(AppRole.SUPER_ADMIN);
            when(audit.recent(anyInt()))
                    .thenReturn(List.of(entry(1, AuditAction.ACCOUNT_APPROVE, "someone.else@example.com")));

            List<NotificationFeedService.FeedItem> feed = service.feedFor(caller, 20);

            assertThat(feed).extracting(NotificationFeedService.FeedItem::actorEmail)
                    .containsExactly("someone.else@example.com");
            verify(audit).recent(anyInt());
            verify(audit, never()).recentForActor(eq(caller.id()), anyInt());
        }

        @Test
        @DisplayName("an admin reads only their own activity")
        void adminSeesOnlySelf() {
            assertScopedToSelf(AppRole.ADMIN);
        }

        @Test
        @DisplayName("a user reads only their own activity")
        void userSeesOnlySelf() {
            assertScopedToSelf(AppRole.USER);
        }

        @Test
        @DisplayName("a guest reads only their own activity")
        void guestSeesOnlySelf() {
            assertScopedToSelf(AppRole.GUEST);
        }

        /**
         * The unscoped query must never be reached for this role — asserted
         * rather than inferred from an empty result, because a stub returning
         * nothing would let a genuine leak pass.
         */
        private void assertScopedToSelf(AppRole role) {
            CurrentUser caller = TestCallers.user(role);
            when(audit.recentForActor(eq(caller.id()), anyInt()))
                    .thenReturn(List.of(entry(1, AuditAction.ACCOUNT_REGISTER, caller.email())));

            List<NotificationFeedService.FeedItem> feed = service.feedFor(caller, 20);

            assertThat(feed).hasSize(1);
            verify(audit).recentForActor(eq(caller.id()), anyInt());
            verify(audit, never()).recent(anyInt());
        }
    }

    @Nested
    @DisplayName("content")
    class Content {

        @Test
        @DisplayName("sign-in and sign-out are filtered out, everything else kept")
        void hidesAuthNoise() {
            CurrentUser caller = TestCallers.user(AppRole.SUPER_ADMIN);
            when(audit.recent(anyInt()))
                    .thenReturn(List.of(
                            entry(1, AuditAction.AUTH_SIGN_IN, "a@example.com"),
                            entry(2, AuditAction.AUTH_SIGN_OUT, "a@example.com"),
                            entry(3, AuditAction.AUTH_REJECTED, "a@example.com"),
                            entry(4, AuditAction.ACCOUNT_APPROVE, "a@example.com")));

            assertThat(service.feedFor(caller, 20))
                    .extracting(NotificationFeedService.FeedItem::summary)
                    .containsExactly("A sign-in attempt was refused", "Approved a registration");
        }

        @Test
        @DisplayName("a feed of nothing but sign-ins is empty, not a page of noise")
        void allHiddenYieldsEmpty() {
            CurrentUser caller = TestCallers.user(AppRole.USER);
            when(audit.recentForActor(eq(caller.id()), anyInt()))
                    .thenReturn(IntStream.range(0, 40)
                            .mapToObj(i -> entry(i, AuditAction.AUTH_SIGN_IN, caller.email()))
                            .toList());

            assertThat(service.feedFor(caller, 20)).isEmpty();
        }

        @Test
        @DisplayName("an unmapped action falls back to its raw key rather than vanishing")
        void unknownActionIsVisible() {
            CurrentUser caller = TestCallers.user(AppRole.SUPER_ADMIN);
            when(audit.recent(anyInt())).thenReturn(List.of(entry(1, "page.something_new", "a@example.com")));

            assertThat(service.feedFor(caller, 20))
                    .extracting(NotificationFeedService.FeedItem::summary)
                    .containsExactly("page.something_new");
        }
    }

    @Nested
    @DisplayName("limit")
    class Limit {

        @Test
        @DisplayName("is clamped, so a caller cannot ask for the whole table")
        void clampsLimit() {
            CurrentUser caller = TestCallers.user(AppRole.SUPER_ADMIN);
            when(audit.recent(anyInt()))
                    .thenReturn(IntStream.range(0, 400)
                            .mapToObj(i -> entry(i, AuditAction.ACCOUNT_APPROVE, "a@example.com"))
                            .toList());

            assertThat(service.feedFor(caller, 10_000)).hasSizeLessThanOrEqualTo(50);
        }

        @Test
        @DisplayName("a non-positive limit is the default, not an error")
        void nonPositiveLimitIsDefault() {
            CurrentUser caller = TestCallers.user(AppRole.SUPER_ADMIN);
            when(audit.recent(anyInt()))
                    .thenReturn(IntStream.range(0, 100)
                            .mapToObj(i -> entry(i, AuditAction.ACCOUNT_APPROVE, "a@example.com"))
                            .toList());

            assertThat(service.feedFor(caller, 0)).hasSize(20);
        }
    }

    @Nested
    @DisplayName("HRMS report arrivals")
    class Hrms {

        private static HrmsNotification report(long id, String subject, Instant receivedAt) {
            return new HrmsNotification(id, "<" + id + "@hrms>", subject, null, receivedAt);
        }

        /**
         * The regression this reserved share exists for.
         *
         * <p>A report arrives once a day; audit_log gains a row per sign-in and
         * per edit. On a busy installation 1,878 audit rows were newer than the
         * newest report against a cap of 50, so a purely time-sorted merge cut
         * every report off the end and the bell showed none at all. It read as
         * broken ingestion; nothing was broken but the arithmetic.
         */
        @Test
        @DisplayName("survive a flood of newer activity rather than being truncated away")
        void surviveNewerActivityFlood() {
            CurrentUser caller = TestCallers.user(AppRole.SUPER_ADMIN);

            // 200 audit rows, every one newer than the report.
            Instant now = Instant.parse("2026-09-28T04:00:00Z");
            List<AuditEntry> flood = IntStream.range(0, 200)
                    .<AuditEntry>mapToObj(
                            i -> entryAt(i + 1L, AuditAction.PAGE_PUBLISH, "a@example.com", now.plusSeconds(i)))
                    .toList();
            when(audit.recent(anyInt())).thenReturn(flood);
            when(hrms.recent(anyInt(), any()))
                    .thenReturn(List.of(report(7, "Attendance on 08-Sep-2026", now.minus(java.time.Duration.ofDays(14)))));

            List<NotificationFeedService.FeedItem> feed = service.feedFor(caller, 50);

            assertThat(feed).hasSize(50);
            assertThat(feed)
                    .extracting(NotificationFeedService.FeedItem::summary)
                    .contains("Attendance on 08-Sep-2026");
        }

        @Test
        @DisplayName("appear in an admin's feed")
        void adminSeesThem() {
            CurrentUser caller = TestCallers.user(AppRole.ADMIN);
            when(hrms.recent(anyInt(), any())).thenReturn(List.of(report(7, "Attendance on 08-Sep-2026", Instant.now())));

            assertThat(service.feedFor(caller, 20))
                    .extracting(NotificationFeedService.FeedItem::summary)
                    .contains("Attendance on 08-Sep-2026");
        }

        /**
         * Not a confidentiality rule — the subject names nobody. An editor has
         * no use for an operational notice about a mailbox, and the feed is
         * more useful for not carrying it.
         *
         * <p><b>That floor is gone (ADR-042).</b> This test asserted the
         * opposite until the attendance popup was added: a report row can now
         * be opened to show the table, and the PM decided every role should
         * see it. The test is rewritten rather than deleted so the change is
         * legible here — a vanished test looks like an oversight, an inverted
         * one looks like a decision.
         */
        @Test
        @DisplayName("appear for every signed-in role, including a plain user")
        void everyRoleSeesThem() {
            CurrentUser caller = TestCallers.user(AppRole.USER);
            when(hrms.recent(anyInt(), any()))
                    .thenReturn(List.of(report(7, "Attendance on 08-Sep-2026", Instant.now())));

            assertThat(service.feedFor(caller, 20))
                    .extracting(NotificationFeedService.FeedItem::summary)
                    .containsExactly("Attendance on 08-Sep-2026");
        }

        /**
         * The reason the two sources are merged and re-sorted rather than
         * concatenated: a report that arrived days ago must not sit above an
         * edit made this morning.
         */
        @Test
        @DisplayName("are interleaved with activity by time, not appended after it")
        void sortedByTimeAcrossSources() {
            CurrentUser caller = TestCallers.user(AppRole.SUPER_ADMIN);
            Instant now = Instant.now();

            when(audit.recent(anyInt()))
                    .thenReturn(List.of(new AuditEntry(
                            1,
                            UUID.randomUUID(),
                            "a@example.com",
                            null,
                            AuditAction.PAGE_PUBLISH,
                            "page",
                            UUID.randomUUID(),
                            null,
                            now)));
            when(hrms.recent(anyInt(), any()))
                    .thenReturn(List.of(report(7, "Attendance on 01-Sep-2026", now.minusSeconds(86_400))));

            assertThat(service.feedFor(caller, 20))
                    .extracting(NotificationFeedService.FeedItem::summary)
                    .containsExactly("Published a page", "Attendance on 01-Sep-2026");
        }

        /**
         * Both sources number their rows from 1, so without a namespace the
         * feed can hand a client two different items with the same key.
         */
        @Test
        @DisplayName("carry ids that cannot collide with an audit row's")
        void idsAreNamespaced() {
            CurrentUser caller = TestCallers.user(AppRole.SUPER_ADMIN);
            when(audit.recent(anyInt()))
                    .thenReturn(List.of(entry(7, AuditAction.ACCOUNT_APPROVE, "a@example.com")));
            when(hrms.recent(anyInt(), any())).thenReturn(List.of(report(7, "Attendance on 08-Sep-2026", Instant.now())));

            assertThat(service.feedFor(caller, 20))
                    .extracting(NotificationFeedService.FeedItem::id)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("are attributed to no one, because no one here did it")
        void haveNoActor() {
            CurrentUser caller = TestCallers.user(AppRole.SUPER_ADMIN);
            when(hrms.recent(anyInt(), any())).thenReturn(List.of(report(7, "Attendance on 08-Sep-2026", Instant.now())));

            assertThat(service.feedFor(caller, 20))
                    .extracting(NotificationFeedService.FeedItem::actorEmail)
                    .containsOnlyNulls();
        }
    }

    @Nested
    @DisplayName("target navigation")
    class TargetNavigation {

        @Test
        @DisplayName("an account target resolves to Users & Roles for a super_admin")
        void accountTargetResolvesForSuperAdmin() {
            CurrentUser caller = TestCallers.user(AppRole.SUPER_ADMIN);
            UUID accountId = UUID.randomUUID();
            when(audit.recent(anyInt())).thenReturn(List.of(new AuditEntry(
                    1, UUID.randomUUID(), "a@example.com", null, AuditAction.ACCOUNT_ROLE_CHANGE,
                    AuditAction.TARGET_ACCOUNT, accountId, null, Instant.now())));

            List<NotificationFeedService.FeedItem> feed = service.feedFor(caller, 20);

            assertThat(feed).singleElement().satisfies(item -> {
                assertThat(item.actionType()).isEqualTo(AuditAction.ACCOUNT_ROLE_CHANGE);
                assertThat(item.targetType()).isEqualTo(AuditAction.TARGET_ACCOUNT);
                assertThat(item.targetId()).isEqualTo(accountId);
                assertThat(item.href()).isEqualTo("/users-roles?highlight=" + accountId);
            });
        }

        /**
         * Defence in depth: a non-super_admin cannot in practice be the actor
         * of an account-management action (the route is super_admin-only), but
         * this asserts the client is never handed a link to a screen it could
         * not open even if a row like this ever reached its own feed.
         */
        @Test
        @DisplayName("an account target never resolves to a link for anyone below super_admin")
        void accountTargetDoesNotResolveBelowSuperAdmin() {
            CurrentUser caller = TestCallers.user(AppRole.ADMIN);
            UUID accountId = UUID.randomUUID();
            when(audit.recentForActor(eq(caller.id()), anyInt())).thenReturn(List.of(new AuditEntry(
                    1, caller.id(), caller.email(), null, AuditAction.ACCOUNT_REGISTER,
                    AuditAction.TARGET_ACCOUNT, accountId, null, Instant.now())));

            assertThat(service.feedFor(caller, 20))
                    .singleElement()
                    .extracting(NotificationFeedService.FeedItem::href)
                    .isNull();
        }

        /**
         * A page target carries a real {@code target_id} (the schema and the
         * admin API both exist), but no frontend route opens one yet — see
         * {@code NotificationFeedService.hrefFor}. This documents that current
         * limitation so the day a page editor ships, this test is what has to
         * change, rather than the gap going unnoticed.
         */
        @Test
        @DisplayName("a page target carries its id but no link, since no page editor exists yet")
        void pageTargetHasNoLinkYet() {
            CurrentUser caller = TestCallers.user(AppRole.SUPER_ADMIN);
            UUID pageId = UUID.randomUUID();
            when(audit.recent(anyInt())).thenReturn(List.of(new AuditEntry(
                    1, UUID.randomUUID(), "a@example.com", null, AuditAction.PAGE_PUBLISH,
                    AuditAction.TARGET_PAGE, pageId, null, Instant.now())));

            List<NotificationFeedService.FeedItem> feed = service.feedFor(caller, 20);

            assertThat(feed).singleElement().satisfies(item -> {
                assertThat(item.targetType()).isEqualTo(AuditAction.TARGET_PAGE);
                assertThat(item.targetId()).isEqualTo(pageId);
                assertThat(item.href()).isNull();
            });
        }

        @Test
        @DisplayName("an HRMS report arrival carries no target at all")
        void hrmsItemHasNoTarget() {
            CurrentUser caller = TestCallers.user(AppRole.ADMIN);
            when(hrms.recent(anyInt(), any()))
                    .thenReturn(List.of(new HrmsNotification(
                            7, "<7@hrms>", "Attendance on 08-Sep-2026", null, Instant.now())));

            assertThat(service.feedFor(caller, 20)).singleElement().satisfies(item -> {
                assertThat(item.actionType()).isNull();
                assertThat(item.targetType()).isNull();
                assertThat(item.targetId()).isNull();
                assertThat(item.href()).isNull();
            });
        }
    }

    @Nested
    @DisplayName("actor picture")
    class ActorPicture {

        @Test
        @DisplayName("carries the actor's current avatar, read live by AuditRepository's join")
        void carriesActorAvatar() {
            CurrentUser caller = TestCallers.user(AppRole.SUPER_ADMIN);
            when(audit.recent(anyInt())).thenReturn(List.of(new AuditEntry(
                    1, UUID.randomUUID(), "a@example.com", "https://cdn.example.com/a.png",
                    AuditAction.ACCOUNT_APPROVE, AuditAction.TARGET_ACCOUNT, UUID.randomUUID(), null, Instant.now())));

            assertThat(service.feedFor(caller, 20))
                    .singleElement()
                    .extracting(NotificationFeedService.FeedItem::actorAvatarUrl)
                    .isEqualTo("https://cdn.example.com/a.png");
        }

        /**
         * The actor has no picture, or the account behind {@code actor_id} has
         * since been deleted — {@code AuditRepository}'s join is a left join
         * for exactly this, and this asserts the null it produces survives
         * through rather than being defaulted to something that reads as a
         * real (missing) picture.
         */
        @Test
        @DisplayName("is null when the actor has no picture")
        void nullWhenNoAvatar() {
            CurrentUser caller = TestCallers.user(AppRole.SUPER_ADMIN);
            when(audit.recent(anyInt()))
                    .thenReturn(List.of(entry(1, AuditAction.ACCOUNT_APPROVE, "a@example.com")));

            assertThat(service.feedFor(caller, 20))
                    .singleElement()
                    .extracting(NotificationFeedService.FeedItem::actorAvatarUrl)
                    .isNull();
        }

        @Test
        @DisplayName("is null for an HRMS report arrival, which has no actor to have one")
        void nullForHrms() {
            CurrentUser caller = TestCallers.user(AppRole.ADMIN);
            when(hrms.recent(anyInt(), any()))
                    .thenReturn(List.of(new HrmsNotification(
                            7, "<7@hrms>", "Attendance on 08-Sep-2026", null, Instant.now())));

            assertThat(service.feedFor(caller, 20))
                    .singleElement()
                    .extracting(NotificationFeedService.FeedItem::actorAvatarUrl)
                    .isNull();
        }
    }

    /**
     * Every action a reader can see has a sentence.
     *
     * <p>{@code summaryFor} falls back to the raw action key, and the fallback
     * is deliberate — a build older than an action should render something true
     * rather than a blank row. What was not deliberate is that nineteen actions
     * lived on it, so the topbar showed lines reading
     * {@code auth.password_change_complete} and
     * {@code claude_usage_member.invite} to people who have never seen a log
     * line. The PM found the first one by changing their own password.
     *
     * <p>Reflection over the constants rather than a list repeated here: a list
     * would have to be updated by the same person, in the same commit, who
     * forgot the {@code case}. This asks the vocabulary itself, so adding an
     * action to {@link AuditAction} and nothing else fails here rather than in
     * somebody's topbar.
     */
    @Nested
    @DisplayName("wording")
    class Wording {

        /**
         * Action constants, which is every {@code String} constant except the
         * {@code TARGET_*} block — those name target types for the
         * {@code target_type} column and never reach a summary.
         */
        private static List<String> visibleActions() {
            return java.util.Arrays.stream(AuditAction.class.getDeclaredFields())
                    .filter(f -> java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                    .filter(f -> f.getType() == String.class)
                    .filter(f -> !f.getName().startsWith("TARGET_"))
                    .map(f -> {
                        try {
                            return (String) f.get(null);
                        } catch (IllegalAccessException e) {
                            throw new AssertionError("could not read " + f.getName(), e);
                        }
                    })
                    .filter(action -> !NotificationFeedService.HIDDEN_ACTIONS.contains(action))
                    .toList();
        }

        @Test
        @DisplayName("no action reaches the feed as its raw key")
        void everyVisibleActionHasASentence() {
            List<String> actions = visibleActions();
            // A guard on the guard: a refactor that renamed the constants or
            // moved them would leave this passing over an empty list.
            assertThat(actions).hasSizeGreaterThan(20);

            CurrentUser caller = TestCallers.user(AppRole.SUPER_ADMIN);
            List<AuditEntry> rows = IntStream.range(0, actions.size())
                    .mapToObj(i -> entry(i + 1, actions.get(i), "a@example.com"))
                    .toList();
            when(audit.recent(anyInt())).thenReturn(rows);

            assertThat(service.feedFor(caller, 50))
                    .extracting(NotificationFeedService.FeedItem::summary)
                    .doesNotContainAnyElementsOf(actions);
        }
    }
}
