package com.coc.docs.notify;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code app.hrms.*} configuration block — the mailbox this platform
 * watches for HRMS attendance reports.
 *
 * <p><b>Disabled by default, and that is not a formality.</b> Everything else
 * in this package degrades to logging when unconfigured; this one reads a
 * person's inbox. An IMAP credential is not scoped to one sender — it opens
 * the whole mailbox, and the poller's filtering is a courtesy it performs on
 * data it has already been handed. So the default is off, and turning it on is
 * a deliberate act by whoever owns the mailbox rather than something that
 * starts happening because a property had a plausible default.
 *
 * @param enabled whether to poll at all. False by default; with it false no
 *     connection is ever opened, whatever else is configured here.
 * @param host the IMAP host, {@code imap.gmail.com} for Gmail
 * @param port the IMAPS port. 993 — implicit TLS, never 143, which is
 *     cleartext and would put the mailbox password on the wire.
 * @param username the mailbox to read
 * @param password the mailbox credential. For Gmail this is a 16-character App
 *     Password, which requires 2-Step Verification on the account; the account
 *     password itself will not authenticate. <b>Never logged anywhere</b> —
 *     see the note on {@link #describe()}.
 * @param sender the only {@code From} address whose mail is recorded.
 *     Everything else in the folder is ignored and never read past its
 *     envelope. This is what keeps "watch for the HRMS report" from becoming
 *     "read this person's email".
 * @param folder the IMAP folder to search, {@code INBOX} unless a Gmail filter
 *     moves the reports somewhere else.
 * @param lookback how far back each run searches. The poller keeps no memory
 *     of where it stopped — it re-reads this window every time and lets the
 *     unique index on {@code message_id} reject what it has already seen. Long
 *     enough that a weekend of downtime loses nothing; short enough that the
 *     search stays cheap.
 * @param retention how long recorded notifications are kept before
 *     {@link HrmsMailPollJob} prunes them. These rows are disposable, which is
 *     the whole reason they are not in {@code audit_log}.
 *
 * <p>// req: FR-OPS-001, NFR-SEC-PII
 */
@ConfigurationProperties(prefix = "app.hrms")
public record HrmsMailProperties(
        boolean enabled,
        String host,
        int port,
        String username,
        String password,
        String sender,
        String folder,
        Duration lookback,
        Duration retention) {

    /**
     * The poll schedule. Every 30 minutes by default.
     *
     * <p>Not more often: the report arrives once a day, and the notification
     * being up to half an hour late costs nothing next to opening an IMAP
     * connection to somebody's mailbox 288 times a day. Spring treats
     * {@code "-"} as "never run", so {@code app.hrms.poll-cron: "-"} stops the
     * schedule without disturbing the rest of this block.
     */
    public static final String POLL_CRON = "${app.hrms.poll-cron:0 */30 * * * *}";

    /** The zone the cron above is read in — the same one the digest uses. */
    public static final String POLL_ZONE = NotificationProperties.DIGEST_ZONE;

    public HrmsMailProperties {
        host = blankTo(host, "imap.gmail.com");
        port = port <= 0 ? 993 : port;
        username = blankTo(username, "");
        password = blankTo(password, "");
        sender = blankTo(sender, "");
        folder = blankTo(folder, "INBOX");
        lookback = lookback == null ? Duration.ofDays(7) : lookback;
        retention = retention == null ? Duration.ofDays(90) : retention;
    }

    /**
     * Whether this block is complete enough to poll with.
     *
     * <p>{@link #enabled} says someone meant to turn it on; this says they
     * finished. Both are required, and the distinction matters because the
     * failure modes differ: not enabled is a decision, enabled-but-incomplete
     * is a mistake worth a warning at startup.
     */
    public boolean sharedInboxUsable() {
        return active() && !username.isBlank() && !password.isBlank();
    }

    /**
     * Whether the feature can do anything at all, with or without a shared
     * mailbox (ADR-084).
     *
     * <p>Split from {@link #sharedInboxUsable} because the two stopped being
     * the same question once accounts could supply their own credentials. A
     * deployment that sets no {@code app.hrms.username} is no longer
     * misconfigured — it is one where every mailbox read is somebody's own,
     * which is the stronger arrangement and now the recommended one.
     *
     * <p>{@code sender} is required either way: it is the filter that keeps a
     * poll to the HRMS mail and out of the rest of an inbox. Without it this
     * would search every message in whatever mailbox it was given, so a blank
     * sender disables the feature rather than widening it.
     */
    public boolean active() {
        return enabled && !sender.isBlank();
    }

    /**
     * This configuration in a form safe to log.
     *
     * <p>The password is not in it, and neither is the local part of the
     * username: a log stream is a wider audience than whoever configured the
     * mailbox, and the same reasoning {@code MailConfigurationReport} applies
     * to the outbound side applies here. Host, port, folder and the sender
     * filter are all that is needed to diagnose "why is nothing arriving", and
     * the sender is an address this platform did not choose and does not own.
     */
    public String describe() {
        String user = username.isBlank() ? "(no username set)" : "a " + domainOf(username) + " account";
        return host + ":" + port + "/" + folder + ", as " + user + ", watching for mail from " + sender;
    }

    private static String domainOf(String address) {
        int at = address.lastIndexOf('@');
        return at < 0 || at == address.length() - 1 ? "(malformed)" : address.substring(at + 1);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
