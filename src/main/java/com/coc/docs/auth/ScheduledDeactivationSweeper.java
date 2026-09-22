package com.coc.docs.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Finalizes self-service deactivations whose grace period has passed
 * (revision of ADR-042).
 *
 * <p>Not a security control the way session expiry is — a pending
 * deactivation grants no extra access in the meantime, it only delays when
 * {@code deactivated_at} is finally set. This just makes that delay actually
 * end on schedule rather than only on the next person to look at the row.
 *
 * <p>// req: FR-AUTH-003, FR-AUTH-007, ADR-042
 */
@Component
public class ScheduledDeactivationSweeper {

    private static final Logger log = LoggerFactory.getLogger(ScheduledDeactivationSweeper.class);

    private final SessionService sessions;

    public ScheduledDeactivationSweeper(SessionService sessions) {
        this.sessions = sessions;
    }

    /**
     * Every 15 minutes. Short enough that "in around 7 days" does not
     * quietly become "in around 7 days and an hour"; long enough that this
     * is a background sweep, not a clock.
     */
    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT2M")
    public void finalizeDue() {
        try {
            int finalized = sessions.finalizeDueScheduledDeactivations();
            if (finalized > 0) {
                log.info("finalized {} self-service deactivation(s) whose grace period elapsed", finalized);
            }
        } catch (RuntimeException e) {
            // A failed sweep must not take down the scheduler thread, and an
            // account whose grace period elapsed stays exactly as reachable
            // (or not) as before -- nothing here is a security boundary that
            // degrades by waiting one more cycle.
            log.warn("scheduled-deactivation sweep failed; will retry on the next run", e);
        }
    }
}
