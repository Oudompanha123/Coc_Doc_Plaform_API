package com.coc.docs.notify;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.coc.docs.announcement.Announcement;
import com.coc.docs.announcement.AnnouncementPublishedEvent;
import com.coc.docs.announcement.AnnouncementRepository;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Delivery of a published announcement (ADR-098): who is told, and that it
 * happens only after the publish has committed.
 *
 * <p>// req: ADR-098
 */
class AnnouncementNotifierTest {

    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");

    private final NotificationService notifications = mock(NotificationService.class);
    private final AnnouncementRepository announcements = mock(AnnouncementRepository.class);
    private final AnnouncementNotifier notifier =
            new AnnouncementNotifier(notifications, announcements, Clock.fixed(NOW, ZoneOffset.UTC));

    private static Announcement announcement(UUID id, String status) {
        return new Announcement(id, "Maintenance on Friday", "Body", "maintenance", status, null, 2,
                NOW.minusSeconds(5), "Admin", NOW.minusSeconds(60), NOW.minusSeconds(5), "Admin");
    }

    @Test
    @DisplayName("tells every active account but the publisher: bell, live nudge and push")
    void notifiesEveryoneButThePublisher() {
        UUID id = UUID.randomUUID();
        UUID publisher = UUID.randomUUID();
        when(announcements.findById(id)).thenReturn(Optional.of(announcement(id, Announcement.STATUS_PUBLISHED)));

        notifier.onPublished(new AnnouncementPublishedEvent(id, publisher));

        verify(notifications).notifyActiveAccounts(
                eq(NotificationType.ANNOUNCEMENT_PUBLISHED),
                eq(new NotificationTarget(id.toString(), null)),
                eq(Map.of("title", "Maintenance on Friday", "category", "maintenance")),
                eq(publisher));
    }

    @Test
    @DisplayName("tells nobody about an announcement withdrawn before delivery ran")
    void skipsWithdrawn() {
        UUID archived = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        when(announcements.findById(archived))
                .thenReturn(Optional.of(announcement(archived, Announcement.STATUS_ARCHIVED)));
        when(announcements.findById(deleted)).thenReturn(Optional.empty());

        notifier.onPublished(new AnnouncementPublishedEvent(archived, UUID.randomUUID()));
        notifier.onPublished(new AnnouncementPublishedEvent(deleted, UUID.randomUUID()));

        verifyNoInteractions(notifications);
    }

    @Test
    @DisplayName("a delivery failure is logged, never thrown back at the publish")
    void swallowsFailures() {
        UUID id = UUID.randomUUID();
        when(announcements.findById(id)).thenReturn(Optional.of(announcement(id, Announcement.STATUS_PUBLISHED)));
        when(notifications.notifyActiveAccounts(any(), any(), any(), any())).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> notifier.onPublished(new AnnouncementPublishedEvent(id, UUID.randomUUID())))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("runs after commit and off the request thread — the fix for open tabs missing the banner")
    void runsAfterCommitAsynchronously() throws NoSuchMethodException {
        // The regression this guards: delivery inside the publish transaction
        // nudged open tabs before the row was visible to them.
        Method handler = AnnouncementNotifier.class.getMethod("onPublished", AnnouncementPublishedEvent.class);
        assertThat(handler.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(handler.getAnnotation(Async.class)).isNotNull();
    }
}
