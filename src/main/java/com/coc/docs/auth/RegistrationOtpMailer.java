package com.coc.docs.auth;

import com.coc.docs.mail.EmailLayout;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Sends the registration verification email, or — where no SMTP is
 * configured — says so loudly instead (ADR-021, same shape as {@link
 * PasswordResetMailer}, which this class deliberately mirrors).
 *
 * <p>{@code JavaMailSender} is taken through an {@link ObjectProvider} for
 * the identical reason {@code PasswordResetMailer} already documents: {@code
 * spring-boot-starter-mail} autoconfigures that bean only when {@code
 * spring.mail.host} is set, and a deployment that has not configured SMTP
 * must boot and serve every other route exactly as it does today.
 *
 * <p><b>The logged-code fallback is a local-development convenience, not a
 * production behaviour</b> — restated here for this class specifically: a
 * deployment that reaches this branch is writing a live, usable-right-now
 * registration code to its log stream. Configuring {@code spring.mail.*}
 * before going live is required follow-up this class cannot complete on its
 * own — {@code application.yml} is off-limits to the AI that wrote this
 * class, per the project's own {@code SEC-001}/{@code SEC-003} rules.
 *
 * <p>// req: ADR-021
 */
@Component
public class RegistrationOtpMailer {

    private static final Logger log = LoggerFactory.getLogger(RegistrationOtpMailer.class);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final EmailLayout layout;
    private final String fromAddress;

    public RegistrationOtpMailer(
            ObjectProvider<JavaMailSender> mailSender,
            EmailLayout layout,
            @Value("${app.mail.from:Coc Docs Platform <noreply@cocdocsplatform.com>}") String fromAddress) {
        this.mailSender = mailSender;
        this.layout = layout;
        this.fromAddress = fromAddress;
    }

    /**
     * Emails {@code code} as plain digits — never a link, there is nothing to
     * click here — or logs it if there is nowhere to send it.
     *
     * <p>Takes the raw code, not its hash — the one place the plaintext code
     * exists outside the moment it was minted, and it leaves this method only
     * inside the email body, never logged in production (see class Javadoc)
     * and never returned to any caller of this class.
     */
    public void sendCode(String toEmail, String code, Duration ttl) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.warn(
                    "no SMTP mail sender configured (spring.mail.host unset); registration code for "
                            + "{} was NOT emailed and is logged here instead — this is acceptable for local "
                            + "development only, never for a production deployment: {}",
                    toEmail,
                    code);
            return;
        }

        try {
            MimeMessage message = sender.createMimeMessage();
            // true = multipart, so a client that won't render HTML still gets the
            // plain-text alternative rather than nothing.
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(toEmail);
            helper.setSubject("Verify your email — Coc Docs Platform");
            helper.setText(plainTextBody(code, ttl), htmlBody(code, ttl));
            sender.send(message);
        } catch (MessagingException e) {
            // A malformed address or similar — not a caller-facing concern
            // (the response has already been decided by the time this runs),
            // but it must not vanish silently either.
            log.error("failed to build registration verification email for {}", toEmail, e);
        } catch (MailException e) {
            // The transport itself refused it -- wrong host, auth failure
            // (Gmail's own SMTP refuses a plain account password; it requires
            // an App Password), connection refused, the sender address
            // rejected by the relay. Unlike MessagingException (thrown while
            // building the message), this is thrown by sender.send() itself
            // and is unchecked -- left uncaught, it would escape this method
            // and fail the whole /register (or /register/resend) request
            // with a raw 500 instead of the caller ever seeing the "check
            // your email" response. See AccountInviteMailer's identical
            // catch, added for the same reason; this one was missing it
            // until a real SMTP failure surfaced it (2026-09-15).
            log.error("could not send the registration verification email to {} — SMTP send failed", toEmail, e);
        }
    }

    private static String plainTextBody(String code, Duration ttl) {
        return "Your Coc Docs Platform verification code is:\n\n"
                + code
                + "\n\nEnter this code to finish creating your account. It expires in "
                + ttl.toMinutes()
                + " minutes and can be used once.\n\n"
                + "If you didn't request this, you can ignore this email — no account will be created.";
    }

    /**
     * Rendered through {@link EmailLayout}, which owns the brand mark, card
     * and typography all three mailers share. This one has no button — the
     * code is typed back into the page the reader already has open, and a
     * link here would be a second, unnecessary way in.
     */
    private String htmlBody(String code, Duration ttl) {
        return layout.page(
                "Your verification code is " + code + " — it expires in " + ttl.toMinutes() + " minutes.",
                EmailLayout.heading("Verify your email")
                        + EmailLayout.lead(
                                "Enter this code to finish creating your Coc Docs Platform account.")
                        + EmailLayout.code(code)
                        + EmailLayout.footnote("This code expires in " + ttl.toMinutes()
                                + " minutes and can only be used once. If you didn't request this, you can "
                                + "safely ignore this email — no account will be created."));
    }
}
