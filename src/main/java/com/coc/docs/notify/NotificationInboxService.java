package com.coc.docs.notify;

import com.coc.docs.auth.CurrentUser;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading and clearing the caller's own notifications.
 *
 * <p>Separate from {@link NotificationService}, which decides who hears about
 * something and sends it. That one is called by publishers and knows about
 * roles and transports; this one is called by a person looking at a bell. They
 * share a table and nothing else, and keeping them apart is what stops the
 * sending path acquiring a notion of "the current user".
 *
 * <p>Every method takes the resolved caller and passes its account id down,
 * never an id from a request. There is no method here that can name somebody
 * else (NFR-SEC-PII).
 *
 * <p>// req: FR-AUTH-008, NFR-SEC-PII, ADR-034
 */
@Service
public class NotificationInboxService {

    /**
     * A ceiling on one page, whatever the caller asks for.
     *
     * <p>The bell shows a handful; this is the wall between "a dropdown asked
     * for too many" and a query that returns ninety days of rows.
     */
    private static final int MAX_LIMIT = 100;

    private final NotificationRepository notifications;
    private final Clock clock;

    public NotificationInboxService(NotificationRepository notifications, Clock clock) {
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Notification> listFor(CurrentUser caller, int limit) {
        // Clamped rather than refused: this drives a decoration, and answering
        // 400 because somebody asked for 500 helps nobody.
        int bounded = Math.clamp(limit, 1, MAX_LIMIT);
        return notifications.findFor(caller.account().id(), bounded);
    }

    @Transactional(readOnly = true)
    public int unreadCountFor(CurrentUser caller) {
        return notifications.countUnread(caller.account().id());
    }

    /**
     * Marks one read.
     *
     * <p>Returns nothing. Whether a row changed is not the caller's business —
     * an unknown id, one already read and one belonging to somebody else all
     * end the same way, and telling them apart would only help someone
     * enumerating other people's notifications.
     */
    @Transactional
    public void markRead(CurrentUser caller, UUID id) {
        notifications.markRead(id, caller.account().id(), clock.instant());
    }

    @Transactional
    public void markAllRead(CurrentUser caller) {
        notifications.markAllRead(caller.account().id(), clock.instant());
    }
}
