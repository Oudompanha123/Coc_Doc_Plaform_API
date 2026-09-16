package com.coc.docs.notify;

import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires the daily digest.
 *
 * <p>Deliberately thin — the schedule and nothing else. Everything the digest
 * actually does lives in {@link ActivityDigestService}, which takes the day as
 * an argument, so re-sending a missed day is a method call rather than a
 * schedule change, and testing it does not involve waiting until 08:00.
 *
 * <p>The window is <b>yesterday</b>, whole. A digest fired at 08:00 covering
 * "the last 24 hours" would report a different slice of a day depending on
 * when it ran, and two adjacent runs would overlap. A named calendar day does
 * not have that problem, and it is also what the subject line claims.
 *
 * <p>Set {@code app.notifications.digest-cron: "-"} to disable it; Spring
 * treats that value as "never run", which switches the digest off without
 * removing the bean or the rest of the configuration.
 *
 * <p>The same instance sends once per schedule. <b>Two replicas send it
 * twice</b> — there is no leader election or scheduler lock here, and adding
 * one is a real decision (ShedLock, a database advisory lock, or an external
 * scheduler) that depends on hosting, which is {@code [DEFERRED]}. Until then
 * this is safe on a single instance and duplicative on more than one; the
 * failure mode is a duplicate email, not incorrect data.
 *
 * <p>// req: FR-OPS-001
 */
@Component
public class ActivityDigestJob {

    private static final Logger log = LoggerFactory.getLogger(ActivityDigestJob.class);

    private final ActivityDigestService digest;

    public ActivityDigestJob(ActivityDigestService digest) {
        this.digest = digest;
    }

    @Scheduled(cron = NotificationProperties.DIGEST_CRON, zone = NotificationProperties.DIGEST_ZONE)
    public void sendYesterdaysDigest() {
        // Which day that is belongs to the service, so that this job and the
        // admin route cannot drift apart about it. Inside the try with
        // everything else: nothing a scheduled method calls may escape it.
        LocalDate yesterday = null;
        try {
            yesterday = digest.yesterday();
            digest.sendDigestFor(yesterday);
        } catch (RuntimeException e) {
            // A scheduled method that throws is logged by Spring and then
            // silently not retried. Catching it here means the reason appears
            // with the context of what it was doing, and — more to the point —
            // that a failing digest can never be the thing that takes the
            // scheduler's thread down with it.
            log.error("content activity digest for {} failed", yesterday, e);
        }
    }
}
