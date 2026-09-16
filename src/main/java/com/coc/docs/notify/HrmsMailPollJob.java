package com.coc.docs.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires the HRMS mailbox poll, and says at startup whether it is on.
 *
 * <p>Thin, like {@link ActivityDigestJob}: the schedule and nothing else.
 * Everything the poll does is in {@link HrmsNotificationService}, which takes
 * no arguments and can be called by hand.
 *
 * <p><b>Two replicas poll twice.</b> There is no scheduler lock here, for the
 * same reason the digest has none — leader election is a real decision that
 * depends on hosting, which is {@code [DEFERRED]}. The consequence is far
 * milder here than for the digest, though: two polls race on
 * {@code insert ... on conflict do nothing}, exactly one wins, and the loser
 * records nothing. Duplicate work, never a duplicate notification.
 *
 * <p>// req: FR-OPS-001, NFR-SEC-PII
 */
@Component
public class HrmsMailPollJob {

    private static final Logger log = LoggerFactory.getLogger(HrmsMailPollJob.class);

    private final HrmsNotificationService notifications;
    private final HrmsMailProperties properties;

    public HrmsMailPollJob(HrmsNotificationService notifications, HrmsMailProperties properties) {
        this.notifications = notifications;
        this.properties = properties;
    }

    /**
     * Says once, at startup, what this is going to do.
     *
     * <p>The same argument as {@code MailConfigurationReport}: a feature that
     * degrades to silence when unconfigured is a feature whose misconfiguration
     * is invisible, and the first symptom is somebody saying the bell never
     * lights up. Three distinguishable states, because they have three
     * different fixes.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        if (!properties.enabled()) {
            log.info("HRMS: mailbox polling is off (app.hrms.enabled is false). No mailbox is read.");
            return;
        }
        if (!properties.usable()) {
            log.warn(
                    "HRMS: polling is enabled but incomplete — app.hrms.username, password and sender "
                            + "must all be set. Nothing will be polled until they are.");
            return;
        }
        log.info("HRMS: polling {}", properties.describe());
    }

    /**
     * Polls, then prunes.
     *
     * <p>In that order and in one method, so pruning happens on a schedule
     * without a second cron expression to keep in step with the first. Pruning
     * deletes by age, so running it more often than necessary costs a cheap
     * indexed delete that usually matches nothing.
     */
    @Scheduled(cron = HrmsMailProperties.POLL_CRON, zone = HrmsMailProperties.POLL_ZONE)
    public void poll() {
        if (!properties.usable()) {
            return;
        }
        try {
            notifications.poll();

            int pruned = notifications.prune();
            if (pruned > 0) {
                log.info("HRMS: pruned {} notification(s) past the {} retention window", pruned, properties.retention());
            }
        } catch (RuntimeException e) {
            // A scheduled method that throws is logged by Spring and then never
            // retried. Caught here so a failure is reported with what it was
            // doing, and so that reading a mailbox can never be the thing that
            // takes the scheduler down with it. The mailbox itself already
            // swallows IMAP failures; this catches everything past that — a
            // database that has gone away, most plausibly.
            log.error("HRMS: mailbox poll failed", e);
        }
    }
}
