package com.coc.docs.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps out expired {@code password_reset_token} rows. Mirrors {@link
 * SessionMaintenance} exactly, for the same reason: not a security control —
 * an expired token is already refused by {@link PasswordResetToken#isUsable},
 * which checks the instant directly — this only keeps the table from growing
 * without bound (ADR-017, required follow-up 4).
 */
@Component
public class PasswordResetTokenMaintenance {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetTokenMaintenance.class);

    private final PasswordResetService resets;

    public PasswordResetTokenMaintenance(PasswordResetService resets) {
        this.resets = resets;
    }

    /** Hourly, offset from {@code SessionMaintenance}'s sweep so the two don't contend. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT10M")
    public void purgeExpiredTokens() {
        try {
            int purged = resets.purgeExpired();
            if (purged > 0) {
                log.info("purged {} expired password reset token rows", purged);
            }
        } catch (RuntimeException e) {
            // A failed sweep must not take down the scheduler thread, and it
            // has no effect on whether expired tokens are honoured.
            log.warn("password reset token purge failed; will retry on the next run", e);
        }
    }
}
