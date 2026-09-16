package com.coc.docs.notify;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Sends notification email to one or more recipients, or — where no SMTP is
 * configured — says so instead of failing.
 *
 * <p>This is the general sender the {@code auth} mailers do not have: they
 * each address exactly one person about one event, and each carries its own
 * copy of the optional-{@code JavaMailSender} dance. This one takes a rendered
 * body and a recipient list, which is what a digest needs.
 *
 * <p><b>Recipients go in BCC by default.</b> A digest addressed to fifteen
 * administrators in {@code To:} publishes fifteen work email addresses to
 * fifteen people and to every mail system in between, and one Reply-All makes
 * it a thread. That is a disclosure decision, so it is made here rather than
 * per call site — see {@link #send} for the {@code To:} variant, which exists
 * for the single-recipient case.
 *
 * <p><b>Failure never propagates.</b> A digest is a report about work, not
 * part of it; the scheduled job that produces it must not retry, alert, or
 * roll anything back because a relay was briefly unreachable. Both failure
 * modes are logged at {@code error}, which is the only place they are visible.
 *
 * <p>// req: FR-OPS-001, ADR-017, ADR-018
 */
@Service
public class NotificationMailService {

    private static final Logger log = LoggerFactory.getLogger(NotificationMailService.class);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final String fromAddress;

    public NotificationMailService(
            ObjectProvider<JavaMailSender> mailSender,
            @Value("${app.mail.from:Coc Docs Platform <noreply@cocdocsplatform.com>}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    /**
     * Sends one multipart message, recipients hidden from each other.
     *
     * @param recipients the addresses; blanks and duplicates are dropped, and
     *     an empty result is a no-op rather than an error — "nobody is
     *     configured to receive this" is a state, not a failure
     * @return true if the message was handed to the transport
     */
    public boolean sendBcc(List<String> recipients, String subject, String plainText, String html) {
        return dispatch(recipients, subject, plainText, html, true);
    }

    /** As {@link #sendBcc}, but the recipients are visible to each other. */
    public boolean send(List<String> recipients, String subject, String plainText, String html) {
        return dispatch(recipients, subject, plainText, html, false);
    }

    private boolean dispatch(
            List<String> recipients, String subject, String plainText, String html, boolean blindCopy) {

        Set<String> addresses = normalise(recipients);
        if (addresses.isEmpty()) {
            log.info("notification '{}' has no recipients — not sent", subject);
            return false;
        }

        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            // Deliberately logs the subject and the recipient count, never the
            // body: unlike the auth mailers, a report body can carry page
            // titles and the names of the people who edited them, and a log
            // stream is a wider audience than the recipient list.
            log.warn(
                    "no SMTP mail sender configured (spring.mail.host unset); notification '{}' for {} "
                            + "recipient(s) was NOT sent. This is expected in local development; configure "
                            + "spring.mail.* before relying on it in a deployment.",
                    subject,
                    addresses.size());
            return false;
        }

        String[] to = addresses.toArray(String[]::new);
        try {
            MimeMessage message = sender.createMimeMessage();
            // true = multipart, so a client that will not render HTML gets the
            // plain-text alternative rather than an empty message.
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            if (blindCopy) {
                // A message with only Bcc recipients is rejected or filed as
                // spam by some relays, so the envelope sender is also the
                // visible To: — which is accurate, it is a message from the
                // system about the system.
                helper.setTo(fromAddress);
                helper.setBcc(to);
            } else {
                helper.setTo(to);
            }
            helper.setSubject(subject);
            helper.setText(plainText, html);
            sender.send(message);
            log.info("sent notification '{}' to {} recipient(s)", subject, addresses.size());
            return true;
        } catch (MessagingException e) {
            // Thrown while building the message — a malformed address, most
            // often, which no amount of retrying fixes.
            log.error("failed to build notification '{}'", subject, e);
            return false;
        } catch (MailException e) {
            // Thrown by send() itself: wrong host, auth failure, connection
            // refused, sender rejected by the relay.
            log.error("could not send notification '{}' — SMTP send failed", subject, e);
            return false;
        }
    }

    /**
     * Trims, drops blanks, and de-duplicates case-insensitively while keeping
     * the given order.
     *
     * <p>Order is kept because a configured recipient list is usually written
     * in a meaningful one, and an email whose recipients shuffle between sends
     * looks, to a reader, like a different email.
     */
    private static Set<String> normalise(List<String> recipients) {
        if (recipients == null) {
            return Set.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        Set<String> lowercased = new LinkedHashSet<>();
        for (String recipient : recipients) {
            if (recipient == null || recipient.isBlank()) {
                continue;
            }
            String trimmed = recipient.trim();
            if (lowercased.add(trimmed.toLowerCase(java.util.Locale.ROOT))) {
                seen.add(trimmed);
            }
        }
        return seen;
    }
}
