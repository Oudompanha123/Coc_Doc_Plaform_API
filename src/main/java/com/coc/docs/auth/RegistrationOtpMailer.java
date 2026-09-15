package com.coc.docs.auth;

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
    private final String fromAddress;

    public RegistrationOtpMailer(
            ObjectProvider<JavaMailSender> mailSender,
            @Value("${app.mail.from:Coc Docs Platform <noreply@cocdocsplatform.com>}") String fromAddress) {
        this.mailSender = mailSender;
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
     * Inline-styled on purpose, same reasoning as {@code
     * PasswordResetMailer.htmlBody}: a {@code <style>} block is stripped by
     * enough mail clients (Outlook desktop chief among them) that it cannot
     * be relied on.
     */
    private static String htmlBody(String code, Duration ttl) {
        return "<!doctype html>"
                + "<html><body style=\"margin:0;padding:32px 16px;background-color:#eef0f8;"
                + "font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"480\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"max-width:480px;width:100%;background-color:#ffffff;border-radius:16px;"
                + "box-shadow:0 4px 24px rgba(40,34,109,0.08);\">"
                + "<tr><td style=\"padding:40px 40px 32px;text-align:center;\">"
                + "<div style=\"width:56px;height:56px;margin:0 auto 24px;border-radius:16px;"
                + "background:linear-gradient(135deg,#6d3ce0,#4c6ff0);color:#ffffff;font-size:20px;"
                + "font-weight:700;line-height:56px;font-family:inherit;\">CD</div>"
                + "<h1 style=\"margin:0 0 12px;font-size:22px;line-height:1.3;color:#16141f;font-weight:800;\">"
                + "Verify your email</h1>"
                + "<p style=\"margin:0 0 24px;font-size:14px;line-height:1.6;color:#6b6b80;\">"
                + "Enter this code to finish creating your Coc Docs Platform account.</p>"
                + "<div style=\"display:inline-block;padding:16px 28px;border-radius:12px;background-color:#f4f2fd;"
                + "font-size:32px;font-weight:800;letter-spacing:8px;color:#16141f;font-family:'SFMono-Regular',"
                + "Consolas,Menlo,monospace;\">" + code + "</div>"
                + "<hr style=\"margin:32px 0 20px;border:none;border-top:1px solid #ececf2;\">"
                + "<p style=\"margin:0;font-size:12px;line-height:1.6;color:#6b6b80;\">"
                + "This code expires in " + ttl.toMinutes() + " minutes and can only be used once. "
                + "If you didn't request this, you can safely ignore this email — no account will be created.</p>"
                + "</td></tr></table>"
                + "<p style=\"margin:20px 0 0;font-size:12px;color:#6b6b80;text-align:center;\">"
                + "Coc Docs Platform — this is an automated message, please don't reply.</p>"
                + "</td></tr></table>"
                + "</body></html>";
    }
}
