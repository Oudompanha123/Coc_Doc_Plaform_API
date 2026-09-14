package com.coc.docs.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps out expired {@code registration_challenge} rows. Mirrors {@link
 * PasswordResetTokenMaintenance} exactly, for the same reason: not a
 * security control — an expired row is already refused by {@link
 * RegistrationChallenge#isUsable}, which checks the instant directly — this
 * only keeps the table from growing without bound as abandoned, never-verified
 * registrations age out (ADR-021, required follow-up 1).
 */
@Component
public class RegistrationOtpMaintenance {

    private static final Logger log = LoggerFactory.getLogger(RegistrationOtpMaintenance.class);

    private final RegistrationOtpService registrations;

    public RegistrationOtpMaintenance(RegistrationOtpService registrations) {
        this.registrations = registrations;
    }

    /** Hourly, offset from {@code SessionMaintenance} and {@code PasswordResetTokenMaintenance} so the three don't contend. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT20M")
    public void purgeExpiredChallenges() {
        try {
            int purged = registrations.purgeExpired();
            if (purged > 0) {
                log.info("purged {} expired registration_challenge rows", purged);
            }
        } catch (RuntimeException e) {
            // A failed sweep must not take down the scheduler thread, and it
            // has no effect on whether expired codes are honoured.
            log.warn("registration challenge purge failed; will retry on the next run", e);
        }
    }
}
