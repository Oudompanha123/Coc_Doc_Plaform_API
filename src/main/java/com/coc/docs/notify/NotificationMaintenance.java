package com.coc.docs.notify;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the bell from becoming an archive.
 *
 * <p>Table hygiene, mirroring {@code DeviceTokenMaintenance} and
 * {@code SessionMaintenance}. An unbounded table behind a badge count is a
 * query that gets slower for ever, and nobody scrolls back three months in a
 * notification list.
 *
 * <p><b>Read and unread alike.</b> Keeping unread rows for ever would mean one
 * ignored notification pinning a row until somebody clicks it, which is a
 * retention policy set by inattention. Ninety days is long enough that nothing
 * is lost that anybody was going to look at.
 *
 * <p>What this is not: the audit trail. {@code audit_log} is append-only and
 * retained at least a year (ADR-003), and every notification this deletes had
 * its underlying event recorded there. Deleting a bell row loses the prompt,
 * never the fact.
 *
 * <p>// req: FR-AUTH-008, NFR-OPS-AUDIT, ADR-034
 */
@Component
public class NotificationMaintenance {

    private static final Logger log = LoggerFactory.getLogger(NotificationMaintenance.class);

    static final Duration KEEP_FOR = Duration.ofDays(90);

    private final NotificationRepository notifications;
    private final Clock clock;

    public NotificationMaintenance(NotificationRepository notifications, Clock clock) {
        this.notifications = notifications;
        this.clock = clock;
    }

    /**
     * Daily, offset from startup so a restart does not run every sweep in this
     * package at once.
     */
    @Scheduled(fixedDelayString = "PT24H", initialDelayString = "PT35M")
    public void purgeOld() {
        int removed = notifications.deleteOlderThan(clock.instant().minus(KEEP_FOR));
        if (removed > 0) {
            log.info("removed {} notification(s) older than {} days", removed, KEEP_FOR.toDays());
        }
    }
}
