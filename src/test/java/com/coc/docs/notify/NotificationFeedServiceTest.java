package com.coc.docs.notify;

import static org.assertj.core.api.Assertions.assertThat;
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
        return new AuditEntry(
                id, UUID.randomUUID(), actorEmail, action, "account", UUID.randomUUID(), null, Instant.now());
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

        @Test
        @DisplayName("appear in an admin's feed")
        void adminSeesThem() {
            CurrentUser caller = TestCallers.user(AppRole.ADMIN);
            when(hrms.recent(anyInt())).thenReturn(List.of(report(7, "Attendance on 08-Sep-2026", Instant.now())));

            assertThat(service.feedFor(caller, 20))
                    .extracting(NotificationFeedService.FeedItem::summary)
                    .contains("Attendance on 08-Sep-2026");
        }

        /**
         * Not a confidentiality rule — the subject names nobody. An editor has
         * no use for an operational notice about a mailbox, and the feed is
         * more useful for not carrying it.
         */
        @Test
        @DisplayName("do not appear below the admin floor")
        void editorDoesNotSeeThem() {
            CurrentUser caller = TestCallers.user(AppRole.USER);

            assertThat(service.feedFor(caller, 20)).isEmpty();
            verify(hrms, never()).recent(anyInt());
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
                            AuditAction.PAGE_PUBLISH,
                            "page",
                            UUID.randomUUID(),
                            null,
                            now)));
            when(hrms.recent(anyInt()))
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
            when(hrms.recent(anyInt())).thenReturn(List.of(report(7, "Attendance on 08-Sep-2026", Instant.now())));

            assertThat(service.feedFor(caller, 20))
                    .extracting(NotificationFeedService.FeedItem::id)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("are attributed to no one, because no one here did it")
        void haveNoActor() {
            CurrentUser caller = TestCallers.user(AppRole.SUPER_ADMIN);
            when(hrms.recent(anyInt())).thenReturn(List.of(report(7, "Attendance on 08-Sep-2026", Instant.now())));

            assertThat(service.feedFor(caller, 20))
                    .extracting(NotificationFeedService.FeedItem::actorEmail)
                    .containsOnlyNulls();
        }
    }
}
