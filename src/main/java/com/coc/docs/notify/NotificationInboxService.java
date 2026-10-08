package com.coc.docs.notify;

import com.coc.docs.account.AppRole;
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
 * <p><b>Registration rows are a super_admin's only, read as well as sent
 * (ADR-099).</b> {@link NotificationService#notifyApprovers} writes them only
 * for accounts holding {@code super_admin}, but a row outlives the role: an
 * account demoted to {@code admin} would otherwise keep seeing, and counting,
 * the approvals it can no longer act on. So they are left out of the list and
 * the badge for anyone not holding {@code super_admin} now.
 *
 * <p>// req: FR-AUTH-008, NFR-SEC-PII, ADR-034, ADR-099
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

    /**
     * The newest {@code limit} notifications, plus every unread one older than
     * those (up to {@link #MAX_LIMIT}), newest first.
     *
     * <p><b>Why the unread are added.</b> The bell's Unread tab is a filter
     * over this list, and its badge is {@link #unreadCountFor} — every unread
     * row. With the newest thirty alone, somebody whose unread rows had been
     * pushed down by newer read ones (an announcement goes to everyone, so
     * that is easy) saw a badge of 5 over an Unread tab saying "You're all
     * caught up". Adding the unread keeps the tab and the badge in agreement;
     * "All" stays the newest {@code limit}, with any older unread rows in
     * their place by date.
     */
    @Transactional(readOnly = true)
    public List<Notification> listFor(CurrentUser caller, int limit) {
        // Clamped rather than refused: this drives a decoration, and answering
        // 400 because somebody asked for 500 helps nobody.
        int bounded = Math.clamp(limit, 1, MAX_LIMIT);
        UUID accountId = caller.account().id();
        List<Notification> recent = notifications.findFor(accountId, bounded);
        List<Notification> unread = notifications.findUnreadFor(accountId, MAX_LIMIT);

        java.util.Map<UUID, Notification> merged = new java.util.LinkedHashMap<>();
        recent.forEach(n -> merged.put(n.id(), n));
        unread.forEach(n -> merged.putIfAbsent(n.id(), n));
        boolean approver = isApprover(caller);
        if (merged.size() == recent.size() && approver) {
            return recent;
        }
        return merged.values().stream()
                .filter(n -> approver || !REGISTRATION.equals(n.type()))
                .sorted(java.util.Comparator.comparing(Notification::createdAt).reversed())
                .toList();
    }

    @Transactional(readOnly = true)
    public int unreadCountFor(CurrentUser caller) {
        UUID accountId = caller.account().id();
        int unread = notifications.countUnread(accountId);
        return isApprover(caller) ? unread : unread - notifications.countUnreadOfType(accountId, REGISTRATION);
    }

    private static final String REGISTRATION = NotificationType.NEW_REGISTRATION.key();

    /** Held, not ranked — the same test {@code notifyApprovers} uses to pick recipients. */
    private static boolean isApprover(CurrentUser caller) {
        return caller.roles().contains(AppRole.SUPER_ADMIN);
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
