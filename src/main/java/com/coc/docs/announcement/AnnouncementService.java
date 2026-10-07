package com.coc.docs.announcement;

import com.coc.docs.audit.AuditAction;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.notify.NotificationRepository;
import com.coc.docs.notify.NotificationType;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Announcement management and delivery (ADR-098).
 *
 * <p><b>Two audiences, two kinds of method.</b> The admin methods see every
 * announcement in every state; the reader methods ({@link #liveForReaders},
 * {@link #liveForReader}) see only what is published and unexpired. Who may
 * call which is {@code SecurityConfig}'s decision, by path, per ADR-011 — this
 * class does not re-check roles.
 *
 * <p><b>The bell row is a pointer, kept in step here.</b> Publishing stores one
 * {@link NotificationType#ANNOUNCEMENT_PUBLISHED} row per recipient carrying
 * the title; editing a published announcement rewrites that title on every
 * copy; unpublishing or deleting removes them. Nothing else writes those rows,
 * so a bell can never offer an announcement that no longer opens.
 *
 * <p>// req: ADR-098, ADR-034, NFR-OPS-AUDIT
 */
@Service
public class AnnouncementService {

    /** Mirrors {@code announcement_title_length} (V69). */
    static final int MAX_TITLE = 150;

    /** Mirrors {@code announcement_body_length} (V69). */
    static final int MAX_BODY = 5000;

    /** Ceiling on the reader list. Announcements are few; this is a wall, not a page size. */
    static final int MAX_LIVE = 50;

    private final AnnouncementRepository announcements;
    private final ApplicationEventPublisher events;
    private final NotificationRepository notifications;
    private final AuditRepository audit;
    private final ObjectMapper json;
    private final Clock clock;

    public AnnouncementService(
            AnnouncementRepository announcements,
            ApplicationEventPublisher events,
            NotificationRepository notifications,
            AuditRepository audit,
            ObjectMapper json,
            Clock clock) {
        this.announcements = announcements;
        this.events = events;
        this.notifications = notifications;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    /** The editable fields, as an admin submitted them. Validated by {@link #validated}. */
    public record Draft(String title, String body, String category, Instant expiresAt) {}

    // --- admin side ----------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Announcement> listAll() {
        return announcements.findAll();
    }

    @Transactional(readOnly = true)
    public Announcement get(UUID id) {
        return announcements.findById(id).orElseThrow(() -> ApiException.notFound("announcement " + id));
    }

    /** Saves a new announcement as a draft. Nobody is told until it is published. */
    @Transactional
    public Announcement create(Draft draft, CurrentUser caller) {
        Draft clean = validated(draft);
        UUID id = announcements.insert(clean.title(), clean.body(), clean.category(), clean.expiresAt(), caller.id());
        record(caller, AuditAction.ANNOUNCEMENT_CREATE, id, clean.title());
        return get(id);
    }

    /**
     * Replaces the editable fields, refusing a stale edit.
     *
     * <p>Allowed in every state, published included — correcting a typo in a
     * live announcement is the common case, and it takes effect for readers at
     * once because they always open the current row. The title on every bell
     * copy is rewritten with it.
     *
     * @param expectedVersion the {@code versionNo} the editor loaded
     */
    @Transactional
    public Announcement update(UUID id, int expectedVersion, Draft draft, CurrentUser caller) {
        Draft clean = validated(draft, get(id).expiresAt());
        boolean updated = announcements.update(
                id,
                expectedVersion,
                clean.title(),
                clean.body(),
                clean.category(),
                clean.expiresAt(),
                caller.id(),
                clock.instant());
        if (!updated) {
            // Missing and stale both arrive here; only one is a 409.
            get(id);
            throw ApiException.conflict(
                    "this announcement was changed by someone else since you opened it -- reload to see their version");
        }
        Announcement saved = get(id);
        if (saved.isPublished()) {
            notifications.updatePayloadFieldForTarget(
                    NotificationType.ANNOUNCEMENT_PUBLISHED, id.toString(), "title", saved.title());
        }
        record(caller, AuditAction.ANNOUNCEMENT_UPDATE, id, saved.title());
        return saved;
    }

    /**
     * Publishes a draft or archived announcement, and tells every active
     * account but the caller — after this transaction commits, through
     * {@link AnnouncementPublishedEvent} and {@code AnnouncementNotifier}, so
     * the bell row, the live nudge and the push never get ahead of the publish
     * itself.
     *
     * <p>Republishing an archived one notifies again: it was withdrawn, its
     * bell rows were removed, and putting it back is news.
     */
    @Transactional
    public Announcement publish(UUID id, CurrentUser caller) {
        Announcement current = get(id);
        if (current.isPublished()) {
            throw ApiException.conflict("this announcement is already published");
        }
        Instant now = clock.instant();
        if (current.expiresAt() != null && !current.expiresAt().isAfter(now)) {
            throw ApiException.badRequest("its expiry date has already passed -- change or clear it before publishing");
        }
        if (!announcements.publish(id, caller.id(), now)) {
            // Somebody else published it between the read and the write. They
            // sent the notifications; sending them again would double every bell.
            throw ApiException.conflict("this announcement is already published");
        }
        record(caller, AuditAction.ANNOUNCEMENT_PUBLISH, id, current.title());
        events.publishEvent(new AnnouncementPublishedEvent(id, caller.id()));
        return get(id);
    }

    /** Takes a published announcement down, and out of every bell. */
    @Transactional
    public Announcement unpublish(UUID id, CurrentUser caller) {
        Announcement current = get(id);
        if (!announcements.unpublish(id, caller.id(), clock.instant())) {
            throw ApiException.conflict("this announcement is not published");
        }
        notifications.deleteForTarget(NotificationType.ANNOUNCEMENT_PUBLISHED, id.toString());
        record(caller, AuditAction.ANNOUNCEMENT_UNPUBLISH, id, current.title());
        return get(id);
    }

    /**
     * Deletes an announcement in any state, and its bell rows with it.
     *
     * <p>The audit row keeps the title, since after this it is the only record
     * that the announcement existed.
     */
    @Transactional
    public void delete(UUID id, CurrentUser caller) {
        Announcement current = get(id);
        notifications.deleteForTarget(NotificationType.ANNOUNCEMENT_PUBLISHED, id.toString());
        announcements.delete(id);
        record(caller, AuditAction.ANNOUNCEMENT_DELETE, id, current.title());
    }

    /** Whether {@code announcement} has lapsed as of now — for the admin list's "Expired" state. */
    public boolean isExpired(Announcement announcement) {
        return announcement.isExpired(clock.instant());
    }

    // --- reader side ---------------------------------------------------------

    /** Published and unexpired, most recent first. */
    @Transactional(readOnly = true)
    public List<Announcement> liveForReaders() {
        return announcements.findLive(clock.instant(), MAX_LIVE);
    }

    /**
     * One announcement, if a reader may see it.
     *
     * <p>A draft, an archived one, an expired one and an unknown id are all
     * the same 404: a reader probing ids learns nothing about what admins
     * have in progress.
     */
    @Transactional(readOnly = true)
    public Announcement liveForReader(UUID id) {
        return announcements.findById(id)
                .filter(a -> a.isLive(clock.instant()))
                .orElseThrow(() -> ApiException.notFound("announcement " + id));
    }

    // --- helpers -------------------------------------------------------------

    /**
     * Trims and checks the editable fields, mirroring V69's constraints so a
     * bad value is a 400 with a reason rather than a constraint violation.
     */
    Draft validated(Draft draft) {
        return validated(draft, null);
    }

    /**
     * As {@link #validated(Draft)}, except that an expiry left as it was is
     * accepted even if it has passed — fixing a typo in an announcement that
     * has already lapsed should not first require inventing a new date.
     */
    Draft validated(Draft draft, Instant unchangedExpiry) {
        String title = draft.title() == null ? "" : draft.title().strip();
        String body = draft.body() == null ? "" : draft.body().strip();
        String category = draft.category() == null || draft.category().isBlank() ? "info" : draft.category().strip();

        if (title.isEmpty()) {
            throw ApiException.badRequest("an announcement needs a title");
        }
        if (title.length() > MAX_TITLE) {
            throw ApiException.badRequest("the title can be at most " + MAX_TITLE + " characters");
        }
        if (body.isEmpty()) {
            throw ApiException.badRequest("an announcement needs a message");
        }
        if (body.length() > MAX_BODY) {
            throw ApiException.badRequest("the message can be at most " + MAX_BODY + " characters");
        }
        if (!Announcement.CATEGORIES.contains(category)) {
            throw ApiException.badRequest("category must be one of info, update, maintenance or important");
        }
        if (draft.expiresAt() != null
                && !draft.expiresAt().equals(unchangedExpiry)
                && !draft.expiresAt().isAfter(clock.instant())) {
            throw ApiException.badRequest("the expiry date must be in the future");
        }
        return new Draft(title, body, category, draft.expiresAt());
    }

    private void record(CurrentUser caller, String action, UUID id, String title) {
        audit.append(
                caller.id(),
                caller.email(),
                action,
                AuditAction.TARGET_ANNOUNCEMENT,
                id,
                json.createObjectNode().put("title", title));
    }
}
