package com.coc.docs.notify;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drops push destinations nobody has used in a long time.
 *
 * <p>Table hygiene, mirroring {@code SessionMaintenance} and
 * {@code PasswordResetTokenMaintenance} — not a security control. FCM reports
 * most dead tokens on send and those are deleted immediately; this catches the
 * ones that simply stop being re-registered because the browser profile is
 * gone, which FCM never mentions because nothing ever asks it to.
 *
 * <p>// req: ADR-025
 */
@Component
public class DeviceTokenMaintenance {

    private static final Logger log = LoggerFactory.getLogger(DeviceTokenMaintenance.class);

    /**
     * Generous on purpose. A token is re-registered whenever the person opens
     * the app, so this only reaches devices unused for months — and deleting
     * one early costs somebody their notifications until they next visit, with
     * nothing to tell them why.
     */
    private static final Duration UNUSED_FOR = Duration.ofDays(180);

    private final DeviceTokenRepository devices;

    public DeviceTokenMaintenance(DeviceTokenRepository devices) {
        this.devices = devices;
    }

    @Scheduled(fixedDelayString = "PT24H", initialDelayString = "PT30M")
    public void purgeUnused() {
        int removed = devices.deleteLastSeenBefore(Instant.now().minus(UNUSED_FOR));
        if (removed > 0) {
            log.info("removed {} device token(s) unused for {} days", removed, UNUSED_FOR.toDays());
        }
    }
}
