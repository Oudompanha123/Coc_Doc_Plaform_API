package com.coc.docs.announcement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.coc.docs.account.AppRole;
import com.coc.docs.audit.AuditAction;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.auth.TestCallers;
import com.coc.docs.notify.NotificationRepository;
import com.coc.docs.notify.NotificationType;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

/**
 * The lifecycle rules of an announcement (ADR-098): who is told when, and
 * that the bell is kept in step with the announcement.
 *
 * <p>Mocked, like {@code NotificationServiceTest}: the claims here are about
 * decisions this class makes. Whether the conditional updates behave under a
 * real database is V69 plus the repository's to get right.
 *
 * <p>// req: ADR-098
 */
class AnnouncementServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");

    private final AnnouncementRepository repository = mock(AnnouncementRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final AuditRepository audit = mock(AuditRepository.class);

    private final AnnouncementService service = new AnnouncementService(
            repository, events, notifications, audit, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

    private final CurrentUser admin = TestCallers.user(AppRole.ADMIN);

    private static Announcement announcement(UUID id, String status, Instant expiresAt) {
        return new Announcement(
                id, "Maintenance", "Down Friday 18:00", "maintenance", status, expiresAt, 3,
                Announcement.STATUS_PUBLISHED.equals(status) ? NOW.minusSeconds(60) : null,
                null, NOW.minusSeconds(3600), NOW.minusSeconds(60), null);
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("trims, and defaults a missing category to info")
        void trimsAndDefaults() {
            var clean = service.validated(new AnnouncementService.Draft("  Hello  ", "\n Body \n", null, null));
            assertThat(clean.title()).isEqualTo("Hello");
            assertThat(clean.body()).isEqualTo("Body");
            assertThat(clean.category()).isEqualTo("info");
        }

        @Test
        @DisplayName("refuses a blank title, an unknown category and a past expiry with 400")
        void refusesBadInput() {
            assertBadRequest(new AnnouncementService.Draft("   ", "Body", "info", null));
            assertBadRequest(new AnnouncementService.Draft("Title", "Body", "gossip", null));
            assertBadRequest(new AnnouncementService.Draft("Title", "Body", "info", NOW.minusSeconds(1)));
            assertBadRequest(new AnnouncementService.Draft("x".repeat(151), "Body", "info", null));
        }

        @Test
        @DisplayName("an edit may keep an expiry that has already passed")
        void keepsAnUnchangedPastExpiry() {
            Instant past = NOW.minus(Duration.ofDays(1));
            var clean = service.validated(new AnnouncementService.Draft("Title", "Body", "info", past), past);
            assertThat(clean.expiresAt()).isEqualTo(past);
        }

        private void assertBadRequest(AnnouncementService.Draft draft) {
            assertThatThrownBy(() -> service.validated(draft))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }

    @Nested
    @DisplayName("publishing")
    class Publishing {

        @Test
        @DisplayName("a draft is published, and the notifier is handed the event to deliver after commit")
        void publishesAndNotifies() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id))
                    .thenReturn(Optional.of(announcement(id, Announcement.STATUS_DRAFT, null)))
                    .thenReturn(Optional.of(announcement(id, Announcement.STATUS_PUBLISHED, null)));
            when(repository.publish(id, admin.id(), NOW)).thenReturn(true);

            Announcement result = service.publish(id, admin);

            assertThat(result.isPublished()).isTrue();
            // Not delivered here: inside this transaction an open tab's re-read
            // would not yet see the row (AnnouncementNotifier's javadoc).
            verify(events).publishEvent(new AnnouncementPublishedEvent(id, admin.id()));
            verify(audit).append(eq(admin.id()), any(), eq(AuditAction.ANNOUNCEMENT_PUBLISH),
                    eq(AuditAction.TARGET_ANNOUNCEMENT), eq(id), any());
        }

        @Test
        @DisplayName("losing a race to another publisher sends nothing a second time")
        void raceSendsNothing() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.of(announcement(id, Announcement.STATUS_DRAFT, null)));
            when(repository.publish(any(), any(), any())).thenReturn(false);

            assertThatThrownBy(() -> service.publish(id, admin))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
            verifyNoInteractions(events);
        }

        @Test
        @DisplayName("an announcement whose expiry has passed cannot be published")
        void refusesExpired() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id))
                    .thenReturn(Optional.of(announcement(id, Announcement.STATUS_ARCHIVED, NOW.minusSeconds(5))));

            assertThatThrownBy(() -> service.publish(id, admin)).isInstanceOf(ApiException.class);
            verify(repository, never()).publish(any(), any(), any());
            verifyNoInteractions(events);
        }
    }

    @Nested
    @DisplayName("keeping the bell in step")
    class BellInStep {

        @Test
        @DisplayName("editing a published announcement rewrites the title and category on every bell copy")
        void editRewritesTitle() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.of(announcement(id, Announcement.STATUS_PUBLISHED, null)));
            when(repository.update(eq(id), eq(3), any(), any(), any(), any(), any(), any())).thenReturn(true);

            service.update(id, 3, new AnnouncementService.Draft("Maintenance", "Body", "maintenance", null), admin);

            verify(notifications).updatePayloadFieldForTarget(
                    NotificationType.ANNOUNCEMENT_PUBLISHED, id.toString(), "title", "Maintenance");
            verify(notifications).updatePayloadFieldForTarget(
                    NotificationType.ANNOUNCEMENT_PUBLISHED, id.toString(), "category", "maintenance");
        }

        @Test
        @DisplayName("a stale edit is a 409 and touches no bell")
        void staleEditConflicts() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.of(announcement(id, Announcement.STATUS_PUBLISHED, null)));
            when(repository.update(any(), anyInt(), any(), any(), any(), any(), any(), any())).thenReturn(false);

            assertThatThrownBy(() -> service.update(
                            id, 1, new AnnouncementService.Draft("T", "B", "info", null), admin))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
            verifyNoInteractions(notifications);
        }

        @Test
        @DisplayName("unpublishing removes it from every bell")
        void unpublishClearsBells() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.of(announcement(id, Announcement.STATUS_PUBLISHED, null)));
            when(repository.unpublish(id, admin.id(), NOW)).thenReturn(true);

            service.unpublish(id, admin);

            verify(notifications).deleteForTarget(NotificationType.ANNOUNCEMENT_PUBLISHED, id.toString());
        }

        @Test
        @DisplayName("deleting removes it from every bell and keeps the title in the audit log")
        void deleteClearsBells() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.of(announcement(id, Announcement.STATUS_DRAFT, null)));

            service.delete(id, admin);

            verify(notifications).deleteForTarget(NotificationType.ANNOUNCEMENT_PUBLISHED, id.toString());
            verify(repository).delete(id);
            verify(audit).append(eq(admin.id()), any(), eq(AuditAction.ANNOUNCEMENT_DELETE),
                    eq(AuditAction.TARGET_ANNOUNCEMENT), eq(id),
                    eq(new ObjectMapper().createObjectNode().put("title", "Maintenance")));
        }
    }

    @Nested
    @DisplayName("reading")
    class Reading {

        @Test
        @DisplayName("a reader gets 404 for a draft and for an expired announcement alike")
        void hidesWhatIsNotLive() {
            UUID draft = UUID.randomUUID();
            UUID expired = UUID.randomUUID();
            when(repository.findById(draft)).thenReturn(Optional.of(announcement(draft, Announcement.STATUS_DRAFT, null)));
            when(repository.findById(expired))
                    .thenReturn(Optional.of(announcement(expired, Announcement.STATUS_PUBLISHED, NOW)));

            for (UUID id : new UUID[] {draft, expired}) {
                assertThatThrownBy(() -> service.liveForReader(id))
                        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
            }
        }

        @Test
        @DisplayName("a reader gets a live announcement")
        void returnsLive() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id))
                    .thenReturn(Optional.of(announcement(id, Announcement.STATUS_PUBLISHED, NOW.plusSeconds(60))));

            assertThat(service.liveForReader(id).id()).isEqualTo(id);
        }
    }
}
