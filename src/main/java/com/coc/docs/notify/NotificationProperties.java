package com.coc.docs.notify;

import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code app.notifications.*} configuration block.
 *
 * <p>Every value has a working default, so a deployment that configures
 * nothing still boots and behaves predictably. What it does <em>not</em> do is
 * send anything: with no SMTP host configured, {@link NotificationMailService}
 * logs instead of sending (the shape {@code PasswordResetMailer} established),
 * and with no recipients resolvable the digest skips itself. Silence in
 * development is the default, not a thing to remember to switch off.
 *
 * @param brandName the name in the footer and the subject prefix
 * @param zone the timezone the digest's day boundaries and clock times are
 *     expressed in. A daily report is only meaningful against a stated day —
 *     and the readers of this one are in Phnom Penh, while the server may not
 *     be, so this is never left to the JVM default.
 * @param recipients explicit recipient addresses. Empty means "every active
 *     admin and super_admin", resolved per send, so an administrator added
 *     next month is included without a config change.
 * @param sendWhenEmpty whether to send a digest covering a day on which
 *     nothing happened. False by default: a recurring email that is usually
 *     empty trains its readers to delete it unread, which costs the days it
 *     is not empty.
 *
 * <p>// req: FR-OPS-001
 */
@ConfigurationProperties(prefix = "app.notifications")
public record NotificationProperties(
        String brandName, String zone, List<String> recipients, boolean sendWhenEmpty) {

    /**
     * The cron placeholder the scheduled job's annotation resolves.
     *
     * <p>Lives here rather than inline in {@link ActivityDigestJob} so the key
     * and its default sit beside the rest of this block. An annotation value
     * must be a compile-time constant, which is why this is a constant rather
     * than a record component like everything else here.
     *
     * <p>Spring treats {@code "-"} as "never run", so setting
     * {@code app.notifications.digest-cron: "-"} disables the digest without
     * removing the bean.
     */
    public static final String DIGEST_CRON = "${app.notifications.digest-cron:0 0 8 * * *}";

    /** The zone the cron expression above is evaluated in. Same key as {@link #zone()}. */
    public static final String DIGEST_ZONE = "${app.notifications.zone:Asia/Phnom_Penh}";

    public NotificationProperties {
        brandName = (brandName == null || brandName.isBlank()) ? "Coc Docs Platform" : brandName;
        zone = (zone == null || zone.isBlank()) ? "Asia/Phnom_Penh" : zone;
        recipients = recipients == null ? List.of() : List.copyOf(recipients);

        // Parsed here, at binding time, so a misspelled zone fails the
        // application at startup rather than at 08:00 inside a scheduled
        // thread — where the exception lands in a log nobody is reading, and
        // the only visible symptom is an email that never arrived.
        ZoneId.of(zone);
    }

    /** The configured zone; already known to parse (see the compact constructor). */
    public ZoneId zoneId() {
        return ZoneId.of(zone);
    }
}
