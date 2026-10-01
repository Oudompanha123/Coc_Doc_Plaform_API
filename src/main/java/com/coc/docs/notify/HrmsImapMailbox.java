package com.coc.docs.notify;

import jakarta.mail.FetchProfile;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.FromTerm;
import jakarta.mail.search.HeaderTerm;
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
        return arrivalsSince(since, configuredLogin());
    }

    /**
     * Reads one person's own inbox (ADR-075).
     *
     * <p>Only {@code login} differs from the configured poll — host, port,
     * folder and the watched sender are shared, and the search is still
     * restricted to {@code app.hrms.sender}. That restriction is a line of
     * code and not a permission: the credential can read the whole mailbox.
     * ADR-075 §4.2 records that rather than leaving it to be noticed.
     */
    @Override
    public List<Arrival> arrivalsSince(Instant since, Login login) {
        if (!usable(login)) {
            return List.of();
        }

        Store store = null;
        Folder folder = null;
        try {
            store = Session.getInstance(sessionProperties()).getStore("imaps");
            store.connect(properties.host(), properties.port(), login.username(), login.password());

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
            log.warn("HRMS: could not read {} ({})", describe(login), e.getMessage());
            return List.of();
        } finally {
            closeQuietly(folder);
            closeQuietly(store);
        }
    }

    /**
     * One message's HTML body, found by {@code Message-ID}.
     *
     * <p>Searched by header rather than kept as a folder index: an IMAP
     * sequence number is only valid for the session that saw it, and the
     * notification row it comes from may be weeks old.
     *
     * <p>The sender term is ANDed in even though the id is unique. The id
     * arrives from a URL, and a caller who guessed one belonging to another
     * mail in the same box must not be handed it — the sender filter is what
     * makes this method able to return only HRMS reports, whatever it is
     * asked for.
     *
     * <p>{@link Folder#READ_ONLY} here too, so opening a report in this
     * platform does not mark it read in somebody's Gmail.
     */
    @Override
    public Optional<String> htmlBodyOf(String messageId) {
        if (!properties.usable()) {
            return Optional.empty();
        }
        return htmlBodyOf(messageId, configuredLogin());
    }

    /** The same, from the owner's mailbox — the only place their copy exists (ADR-075). */
    @Override
    public Optional<String> htmlBodyOf(String messageId, Login login) {
        if (!usable(login) || messageId == null || messageId.isBlank()) {
            return Optional.empty();
        }

        Store store = null;
        Folder folder = null;
        try {
            store = Session.getInstance(sessionProperties()).getStore("imaps");
            store.connect(properties.host(), properties.port(), login.username(), login.password());

            folder = store.getFolder(properties.folder());
            if (!folder.exists()) {
                return Optional.empty();
            }
            folder.open(Folder.READ_ONLY);

            Message[] found = folder.search(new AndTerm(
                    new FromTerm(new InternetAddress(properties.sender())),
                    new HeaderTerm("Message-ID", messageId.trim())));

            if (found.length == 0) {
                // Deleted from the mailbox since it was recorded. The
                // notification row outlives the mail, so this is ordinary.
                log.debug("HRMS: no message with the requested id is still in {}", properties.folder());
                return Optional.empty();
            }
            return Optional.ofNullable(htmlPartOf(found[0]));

        } catch (MessagingException | java.io.IOException e) {
            log.warn("HRMS: could not read a report body ({})", e.getMessage());
            return Optional.empty();
        } finally {
            closeQuietly(folder);
            closeQuietly(store);
        }
    }

    /**
     * The {@code text/html} part, depth-first.
     *
     * <p>These reports arrive {@code multipart/MIXED} wrapping a
     * {@code multipart/ALTERNATIVE}, so the HTML is two levels down and a flat
     * look at the top-level parts finds only the plain-text alternative.
     */
    private static String htmlPartOf(Part part) throws MessagingException, java.io.IOException {
        if (part.isMimeType("text/html")) {
            Object content = part.getContent();
            return content instanceof String html ? html : null;
        }
        if (part.isMimeType("multipart/*") && part.getContent() instanceof Multipart parts) {
            for (int i = 0; i < parts.getCount(); i++) {
                String html = htmlPartOf(parts.getBodyPart(i));
                if (html != null) {
                    return html;
                }
            }
        }
        return null;
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

    /**
     * The configured mailbox, as a login.
     *
     * <p>So that the shared poll and a personal poll travel the same code
     * path. The alternative — a null meaning "use the properties" threaded
     * through both methods — gives two connection routines that can drift, and
     * the one that drifts is the one nobody is watching.
     */
    private Login configuredLogin() {
        return new Login(properties.username(), properties.password());
    }

    /**
     * Whether this login can be attempted at all.
     *
     * <p>{@code properties.usable()} still gates the host and the watched
     * sender, which every login shares; this adds only the credential. A blank
     * one is not logged: for a personal mailbox it means a stored credential
     * failed to decrypt, which the repository has already reported, and
     * repeating it every thirty minutes per account would bury the mailbox
     * failures that matter.
     */
    /**
     * Names a mailbox in a log line without naming the person.
     *
     * <p>The configured one keeps {@code properties.describe()}, which already
     * masks. A personal one gets its local part truncated: a poll that fails
     * for one account out of ten has to be attributable, and "a g***@gmail.com
     * account" is not. Three characters identify it to someone with the
     * account list and to nobody reading a log in isolation.
     */
    private String describe(Login login) {
        if (login == null || login.username() == null || login.username().isBlank()) {
            return properties.describe();
        }
        if (login.username().equals(properties.username())) {
            return properties.describe();
        }
        String user = login.username();
        int at = user.indexOf('@');
        String local = at < 0 ? user : user.substring(0, at);
        String shown = local.length() <= 3 ? local : local.substring(0, 3) + "...";
        return "the mailbox of " + shown + (at < 0 ? "" : user.substring(at));
    }

    private boolean usable(Login login) {
        return properties.usable()
                && login != null
                && login.username() != null
                && !login.username().isBlank()
                && login.password() != null
                && !login.password().isBlank();
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
