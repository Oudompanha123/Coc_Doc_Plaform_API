package com.coc.docs.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.coc.docs.account.AppRole;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.auth.TestCallers;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the bell's list holds, so its Unread tab agrees with its badge.
 *
 * <p>// req: FR-AUTH-008, ADR-034
 */
class NotificationInboxServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");

    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final NotificationInboxService inbox = new NotificationInboxService(repository, Clock.systemUTC());
    private final CurrentUser caller = TestCallers.user(AppRole.USER);

    private Notification row(int minutesAgo, boolean read) {
        Instant at = NOW.minusSeconds(minutesAgo * 60L);
        return new Notification(UUID.randomUUID(), caller.id(), "announcement.published", "a", null, at, read ? at : null);
    }

    @Test
    @DisplayName("an unread row older than the newest page is still listed, in date order")
    void includesOlderUnread() {
        Notification newestRead = row(1, true);
        Notification newerRead = row(2, true);
        Notification oldUnread = row(600, false);
        when(repository.findFor(eq(caller.id()), eq(2))).thenReturn(List.of(newestRead, newerRead));
        when(repository.findUnreadFor(eq(caller.id()), anyInt())).thenReturn(List.of(oldUnread));

        // The badge would say 1; before, the Unread tab had nothing to show.
        assertThat(inbox.listFor(caller, 2)).containsExactly(newestRead, newerRead, oldUnread);
    }

    @Test
    @DisplayName("an unread row already on the page is not listed twice")
    void noDuplicates() {
        Notification unread = row(1, false);
        Notification read = row(2, true);
        when(repository.findFor(eq(caller.id()), anyInt())).thenReturn(List.of(unread, read));
        when(repository.findUnreadFor(eq(caller.id()), anyInt())).thenReturn(List.of(unread));

        assertThat(inbox.listFor(caller, 30)).containsExactly(unread, read);
    }

    @Test
    @DisplayName("an admin is not shown, or counted, registration rows; a super_admin is (ADR-099)")
    void registrationsAreSuperAdminOnly() {
        CurrentUser admin = TestCallers.user(AppRole.ADMIN);
        CurrentUser superAdmin = TestCallers.user(AppRole.SUPER_ADMIN);
        Notification registration = new Notification(
                UUID.randomUUID(), admin.id(), "registration.pending", "acct", null, NOW, null);
        Notification announcement = new Notification(
                UUID.randomUUID(), admin.id(), "announcement.published", "a", null, NOW.minusSeconds(60), null);
        when(repository.findFor(eq(admin.id()), anyInt())).thenReturn(List.of(registration, announcement));
        when(repository.findUnreadFor(eq(admin.id()), anyInt())).thenReturn(List.of(registration, announcement));
        when(repository.countUnread(admin.id())).thenReturn(2);
        when(repository.countUnreadOfType(admin.id(), "registration.pending")).thenReturn(1);

        assertThat(inbox.listFor(admin, 30)).containsExactly(announcement);
        assertThat(inbox.unreadCountFor(admin)).isEqualTo(1);

        when(repository.findFor(eq(superAdmin.id()), anyInt())).thenReturn(List.of(registration, announcement));
        when(repository.findUnreadFor(eq(superAdmin.id()), anyInt())).thenReturn(List.of(registration, announcement));
        when(repository.countUnread(superAdmin.id())).thenReturn(2);

        assertThat(inbox.listFor(superAdmin, 30)).containsExactly(registration, announcement);
        assertThat(inbox.unreadCountFor(superAdmin)).isEqualTo(2);
    }
}
