package com.coc.docs.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps out session rows whose expiry is long past.
 *
 * <p>Not a security control -- an expired session is already refused by
 * {@link SessionService#resolve}, which checks the instant rather than trusting
 * that a sweep has run. This only keeps the table from growing without bound.
 *
 * <p>// req: NFR-SEC-SESSION, NFR-OPS-BACKUP
 */
@Component
public class SessionMaintenance {

    private static final Logger log = LoggerFactory.getLogger(SessionMaintenance.class);

    private final SessionService sessions;

    public SessionMaintenance(SessionService sessions) {
        this.sessions = sessions;
    }

    /** Hourly. The table holds one row per sign-in, so there is nothing to hurry about. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    public void purgeExpiredSessions() {
        try {
            int purged = sessions.purgeExpired();
            if (purged > 0) {
                log.info("purged {} expired session rows", purged);
            }
        } catch (RuntimeException e) {
            // A failed sweep must not take down the scheduler thread, and it has
            // no effect on whether expired sessions are honoured.
            log.warn("session purge failed; will retry on the next run", e);
        }
    }
}
