package com.coc.docs.notify;

import jakarta.mail.FetchProfile;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.FromTerm;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.SentDateTerm;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads envelopes from a real IMAP mailbox — the Gmail account HRMS mails its
 * attendance report to.
 *
 * <p>Uses the {@code jakarta.mail} implementation that already arrives with
 * {@code spring-boot-starter-mail}; no dependency was added for this. Spring's
 * {@code JavaMailSender} is outbound only and has no inbound counterpart, so
 * the session is built here by hand.
 *
 * <p><b>The folder is opened {@link Folder#READ_ONLY}.</b> Opening it
 * read-write would let the server mark the report {@code \Seen} as a side
 * effect of this job looking at it, and the owner of the mailbox would find
 * their mail already read by something they did not do. It also means this
 * class cannot delete, move or flag anything, which is a property worth having
 * structurally rather than by intention.
 *
 * <p><b>Bodies are never fetched.</b> The fetch profile asks for envelopes,
 * and nothing here calls {@code getContent()}. See
 * {@code V21__hrms_notification.sql} for why the report's contents are not
 * wanted: it names ~60 people and their working hours, and this platform has
 * no business holding that.
 *
 * <p>// req: FR-OPS-001, NFR-SEC-PII
 */
@Component
public class HrmsImapMailbox implements HrmsMailbox {

    private static final Logger log = LoggerFactory.getLogger(HrmsImapMailbox.class);

    /**
     * Socket timeouts, in milliseconds.
     *
     * <p>Set explicitly because the {@code jakarta.mail} default is <em>no
     * timeout at all</em>: a connection that stalls mid-handshake blocks its
     * thread forever, and this runs on the shared scheduler. A poll that gives
     * up after half a minute and tries again in thirty is strictly better than
     * one that holds a scheduler thread until the process restarts.
     */
    private static final String TIMEOUT_MS = "30000";

    private final HrmsMailProperties properties;

    public HrmsImapMailbox(HrmsMailProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<Arrival> arrivalsSince(Instant since) {
        if (!properties.usable()) {
            // Not an error and not logged: the job checks this too and says so
            // once at startup rather than every thirty minutes.
            return List.of();
        }

        Store store = null;
        Folder folder = null;
        try {
            store = Session.getInstance(sessionProperties()).getStore("imaps");
            store.connect(properties.host(), properties.port(), properties.username(), properties.password());

            folder = store.getFolder(properties.folder());
            if (!folder.exists()) {
                log.warn(
                        "HRMS: IMAP folder '{}' does not exist on {}; nothing will ever be found there",
                        properties.folder(),
                        properties.host());
                return List.of();
            }
            folder.open(Folder.READ_ONLY);

            Message[] found = folder.search(searchTerm(since));

            // One round trip for all the headers rather than one per message.
            // Without this every getSubject() below is its own IMAP FETCH, and
            // a morning's mail becomes dozens of round trips to Google.
            FetchProfile envelopes = new FetchProfile();
            envelopes.add(FetchProfile.Item.ENVELOPE);
            folder.fetch(found, envelopes);

            List<Arrival> arrivals = new ArrayList<>(found.length);
            for (Message message : found) {
                toArrival(message).ifPresent(arrivals::add);
            }
            arrivals.sort(Comparator.comparing(arrival -> arrival.sentAt()));

            log.debug("HRMS: {} message(s) from {} since {}", arrivals.size(), properties.sender(), since);
            return List.copyOf(arrivals);

        } catch (MessagingException e) {
            // Deliberately swallowed, per the HrmsMailbox contract. An
            // unreachable mailbox, an expired App Password and a Google outage
            // are all "try again in thirty minutes", and none of them is worth
            // unwinding a scheduled job over. Logged at warn with the reason,
            // never with the credential.
            log.warn("HRMS: could not read {} ({})", properties.describe(), e.getMessage());
            return List.of();
        } finally {
            closeQuietly(folder);
            closeQuietly(store);
        }
    }

    /**
     * The search: this sender, at or after this instant.
     *
     * <p>{@link SentDateTerm} rather than a received-date term. Gmail supports
     * both, but a received-date search is an optional IMAP capability, and the
     * {@code Date} header is what {@link Arrival#sentAt} reports anyway — so
     * the search and the value agree by construction.
     *
     * <p>The server compares dates at day granularity, so this returns rather
     * more than asked for. That is fine and slightly desirable: the surplus is
     * rejected by the unique index on {@code message_id}, and a window with
     * slack in it cannot lose a message to a clock skew between here and
     * Google.
     */
    private SearchTerm searchTerm(Instant since) throws MessagingException {
        try {
            return new AndTerm(
                    new FromTerm(new InternetAddress(properties.sender())),
                    new SentDateTerm(ComparisonTerm.GE, Date.from(since)));
        } catch (jakarta.mail.internet.AddressException e) {
            // A malformed app.hrms.sender. Thrown on as a MessagingException so
            // the single catch above logs it with the rest of the
            // configuration, which is the context that makes it obvious.
            throw new MessagingException("app.hrms.sender is not a valid address: " + properties.sender(), e);
        }
    }

    /**
     * One message's envelope, or nothing if it cannot be identified.
     *
     * <p>A message with no {@code Message-ID} is dropped rather than given a
     * synthetic one. The id is the only thing stopping the same report being
     * announced on every poll for a week, and an invented id is a duplicate
     * notification with extra steps. Every real mail server sets one; a
     * message without it is strange enough to deserve a log line.
     */
    private Optional<Arrival> toArrival(Message message) {
        try {
            String[] ids = message.getHeader("Message-ID");
            if (ids == null || ids.length == 0 || ids[0] == null || ids[0].isBlank()) {
                log.warn("HRMS: a message from {} has no Message-ID; skipped", properties.sender());
                return Optional.empty();
            }

            String subject = message.getSubject() == null ? "" : message.getSubject();

            // No Date header is odd but not disqualifying: the mail did arrive,
            // and "now" is a worse answer than the header but a much better one
            // than dropping the notification.
            Date sent = message.getSentDate();
            Instant sentAt = sent == null ? Instant.now() : sent.toInstant();

            return Optional.of(new Arrival(ids[0].trim(), subject, sentAt));
        } catch (MessagingException e) {
            log.warn("HRMS: could not read a message envelope ({}); skipped", e.getMessage());
            return Optional.empty();
        }
    }

    private Properties sessionProperties() {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", properties.host());
        props.put("mail.imaps.port", Integer.toString(properties.port()));
        // Implicit TLS on 993. Belt and braces with the protocol name above,
        // because "imaps" selects the provider and this selects the transport,
        // and a misconfigured port with only one of them set is cleartext.
        props.put("mail.imaps.ssl.enable", "true");
        props.put("mail.imaps.connectiontimeout", TIMEOUT_MS);
        props.put("mail.imaps.timeout", TIMEOUT_MS);
        props.put("mail.imaps.writetimeout", TIMEOUT_MS);
        return props;
    }

    /**
     * Closes a folder or store, swallowing the failure.
     *
     * <p>This runs in a {@code finally} after the result is already decided.
     * A close that fails has nothing to tell the caller — the envelopes were
     * either read or they were not — and letting it throw here would replace a
     * useful warning with a useless one.
     */
    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            log.debug("HRMS: closing {} failed ({})", closeable.getClass().getSimpleName(), e.getMessage());
        }
    }
}
