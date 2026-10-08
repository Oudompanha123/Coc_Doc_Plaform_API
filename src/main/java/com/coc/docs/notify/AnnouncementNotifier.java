package com.coc.docs.notify;

import com.coc.docs.announcement.Announcement;
import com.coc.docs.announcement.AnnouncementPublishedEvent;
import com.coc.docs.announcement.AnnouncementRepository;
import com.coc.docs.announcement.AnnouncementText;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells every active account that an announcement was published (ADR-098) —
 * the bell row, the live-stream nudge to open tabs, and the push to every
 * registered device.
 *
 * <p>The same shape as {@link AxRenewalCommentNotifier}: {@code AFTER_COMMIT},
 * and {@code @Async} so a slow push service never sits inside the request of
 * the admin who pressed Publish.
 *
 * <p><b>Why after the commit, and not inline in the publish.</b> It was inline
 * at first, and open tabs showed no banner: {@link NotificationStream} nudged
 * each tab inside the publishing transaction, the tab re-read its inbox at
 * once, and the new row was not yet visible to it — so there was nothing new
 * to alert about until the next minute's poll. {@link NotificationStream#publish}
 * documents that it is called after the commit; this is what keeps that true.
 *
 * <p>The announcement is re-read here rather than carried on the event: if an
 * admin unpublished or deleted it in the moment between commit and this
 * running, nobody should be told about something already withdrawn.
 *
 * <p>// req: ADR-098, ADR-034, ADR-025, ADR-041, ADR-103
 */
@Component
public class AnnouncementNotifier {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementNotifier.class);

    /** The same names the editor shows (frontend {@code CATEGORY_LABELS}). */
    private static final Map<String, String> TYPE_LABELS = Map.of(
            "info", "Information",
            "update", "Update",
            "maintenance", "Maintenance",
            "important", "Important");

    private final NotificationService notifications;
    private final AnnouncementRepository announcements;
    private final Clock clock;

    public AnnouncementNotifier(NotificationService notifications, AnnouncementRepository announcements, Clock clock) {
        this.notifications = notifications;
        this.announcements = announcements;
        this.clock = clock;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPublished(AnnouncementPublishedEvent event) {
        try {
            Optional<Announcement> current = announcements.findById(event.announcementId());
            if (current.isEmpty() || !current.get().isLive(clock.instant())) {
                log.info("announcement {} was withdrawn before anyone was told; not notifying", event.announcementId());
                return;
            }
            Announcement announcement = current.get();
            String excerpt = AnnouncementText.excerpt(announcement.body(), AnnouncementText.BANNER_EXCERPT_LENGTH);
            int stored = notifications.notifyActiveAccounts(
                    NotificationType.ANNOUNCEMENT_PUBLISHED,
                    new NotificationTarget(announcement.id().toString(), null),
                    // The excerpt rides in the bell row too, so a tab that is
                    // open raises the same banner the push would have.
                    Map.of(
                            "title", announcement.title(),
                            "category", announcement.category(),
                            "excerpt", excerpt),
                    event.publisherId(),
                    banner(announcement, excerpt));
            log.info("announcement {} published; notified {} account(s)", announcement.id(), stored);
        } catch (RuntimeException e) {
            // The announcement is published whether or not anybody hears about
            // it, and members still see it from the reader route. Logged
            // loudly, because a silent failure here looks exactly like
            // "nobody got the push".
            log.error("could not notify members about announcement {}", event.announcementId(), e);
        }
    }

    /**
     * The push banner: the announcement's type and title and the start of its
     * text, with the logo as its icon (ADR-103, revising ADR-098 decision 3 — which pushed
     * only "There is a new announcement" — on product-owner instruction).
     *
     * <p>This puts the announcement on lock screens. That is the point of an
     * announcement, and it holds nothing personal: it is written for every
     * member at once. Other types keep their fixed wording.
     */
    static PushBanner banner(Announcement announcement, String excerpt) {
        String label = TYPE_LABELS.getOrDefault(announcement.category(), "Announcement");
        return new PushBanner(
                label + " · " + announcement.title(),
                excerpt,
                // No large picture: the logo already shows as the banner's
                // icon, and a second, banner-wide copy above the text pushed
                // the announcement itself down (product-owner feedback).
                null,
                // Its own tag, so a second announcement is shown rather than
                // silently replacing the first one still in the OS's
                // notification centre. The background and foreground copies
                // of this one still share it, so they do not stack.
                NotificationType.ANNOUNCEMENT_PUBLISHED.key() + ":" + announcement.id());
    }
}
