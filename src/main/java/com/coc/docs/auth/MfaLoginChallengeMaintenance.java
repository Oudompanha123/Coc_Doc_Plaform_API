package com.coc.docs.auth;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps out expired {@code mfa_login_challenge} rows (ADR-028). Mirrors
 * {@link RegistrationOtpMaintenance} exactly, for the same reason: not a
 * security control — an expired row is already refused by {@link
 * MfaLoginChallenge#isUsable}, which checks the instant directly — this only
 * keeps the table from growing without bound as abandoned sign-in attempts
 * (password proved, TOTP code never entered) age out.
 */
@Component
public class MfaLoginChallengeMaintenance {

    private static final Logger log = LoggerFactory.getLogger(MfaLoginChallengeMaintenance.class);

    private final MfaLoginChallengeRepository challenges;
    private final Clock clock;

    public MfaLoginChallengeMaintenance(MfaLoginChallengeRepository challenges, Clock clock) {
        this.challenges = challenges;
        this.clock = clock;
    }

    /** Hourly, offset from the other three auth-table sweeps so none of the four contend. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT40M")
    public void purgeExpiredChallenges() {
        try {
            int purged = challenges.deleteExpiredBefore(clock.instant());
            if (purged > 0) {
                log.info("purged {} expired mfa_login_challenge rows", purged);
            }
        } catch (RuntimeException e) {
            // A failed sweep must not take down the scheduler thread, and it
            // has no effect on whether expired challenges are honoured.
            log.warn("mfa login challenge purge failed; will retry on the next run", e);
        }
    }
}
