package com.coc.docs.auth;

import com.coc.docs.config.AppProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Sends the password-reset (or, for a Google-only account, first-password
 * "set") email, or — where no SMTP is configured — says so loudly instead
 * (ADR-018 decision 5).
 *
 * <p>{@code JavaMailSender} is taken through an {@link ObjectProvider}, the
 * identical shape {@code SessionService} already uses for {@code
 * KeycloakIdTokenService}: {@code spring-boot-starter-mail} autoconfigures
 * that bean only when {@code spring.mail.host} is set, and a deployment that
 * has not configured SMTP must boot and serve every other route exactly as it
 * does today.
 *
 * <p><b>The logged-link fallback is a local-development convenience, not a
 * production behaviour.</b> Stated plainly, the way ADR-014 states its own
 * gaps: a deployment that reaches this branch is writing a live
 * password-reset credential to its log stream. Configuring {@code
 * spring.mail.*} before going live is required follow-up this class cannot
 * complete on its own — {@code application.yml} is off-limits to the AI that
 * wrote this class, per the project's own {@code SEC-001}/{@code SEC-003}
 * rules.
 *
 * <p>// req: ADR-018, ADR-029
 */
@Component
public class PasswordResetMailer {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetMailer.class);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final AppProperties properties;
    private final String fromAddress;

    public PasswordResetMailer(
            ObjectProvider<JavaMailSender> mailSender,
            AppProperties properties,
            @Value("${app.mail.from:Coc Docs Platform <noreply@cocdocsplatform.com>}") String fromAddress) {
        this.mailSender = mailSender;
        this.properties = properties;
        this.fromAddress = fromAddress;
    }

    /**
     * Emails {@code resetToken} as a link to the frontend's reset-password
     * page, or logs that link if there is nowhere to send it.
     *
     * <p>Takes the raw token, not its hash — this is the one place the
     * plaintext token exists outside the moment it was minted, and it leaves
     * this method only inside the email body, never logged in production (see
     * class Javadoc) and never returned to any caller of this class.
     *
     * <p>{@code hadExistingPassword} (ADR-029) only changes the emailed
     * wording — "reset" for an account that already had a password, "set"
     * for a Google-only account using this as its first one. Safe to
     * differ on, unlike the API response: an email only ever reaches the
     * inbox of whoever controls that address, so wording it accurately
     * costs nothing ADR-018 decision 3's anti-enumeration property depends
     * on.
     */
    public void sendResetLink(String toEmail, String resetToken, boolean hadExistingPassword) {
        String resetUrl = properties.frontendOrigin() + "/reset-password?token=" + resetToken;

        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.warn(
                    "no SMTP mail sender configured (spring.mail.host unset); password {} link for "
                            + "{} was NOT emailed and is logged here instead — this is acceptable for local "
                            + "development only, never for a production deployment: {}",
                    hadExistingPassword ? "reset" : "set",
                    toEmail,
                    resetUrl);
            return;
        }

        try {
            MimeMessage message = sender.createMimeMessage();
            // true = multipart, so a client that won't render HTML still gets the
            // plain-text alternative rather than nothing.
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(toEmail);
            helper.setSubject(hadExistingPassword
                    ? "Reset your Coc Docs Platform password"
                    : "Set your Coc Docs Platform password");
            helper.setText(plainTextBody(resetUrl, hadExistingPassword), htmlBody(resetUrl, hadExistingPassword));
            sender.send(message);
        } catch (MessagingException e) {
            // A malformed address or similar — not a caller-facing concern (ADR-018
            // decision 3 already means this failure must not change the response),
            // but it must not vanish silently either.
            log.error("failed to build password reset email for {}", toEmail, e);
        }
    }

    private static String plainTextBody(String resetUrl, boolean hadExistingPassword) {
        String verb = hadExistingPassword ? "reset" : "set";
        return "We received a request to " + verb + " your Coc Docs Platform password.\n\n"
                + (hadExistingPassword ? "Reset it" : "Set it") + " here (this link expires in 30 minutes and can be used once):\n"
                + resetUrl
                + "\n\n"
                + "If you didn't request this, you can ignore this email — your password will not change.";
    }

    /**
     * Inline-styled on purpose: a {@code <style>} block is stripped by enough
     * mail clients (Outlook desktop chief among them) that it cannot be relied
     * on, and the brand mark is a plain colored badge rather than an image or
     * inline SVG for the same reason — this has no asset host to point an
     * {@code <img>} at, and SVG support in email is inconsistent.
     */
    private static String htmlBody(String resetUrl, boolean hadExistingPassword) {
        String verb = hadExistingPassword ? "reset" : "set";
        String buttonLabel = hadExistingPassword ? "Reset password" : "Set password";
        String font = "-apple-system,BlinkMacSystemFont,'Segoe UI Variable','Segoe UI',"
                + "'Helvetica Neue',Roboto,Helvetica,Arial,sans-serif";
        return "<!doctype html>"
                + "<html><body style=\"margin:0;padding:40px 16px;background-color:#f1f2fa;"
                + "font-family:" + font + ";-webkit-font-smoothing:antialiased;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"480\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"max-width:480px;width:100%;background-color:#ffffff;border-radius:20px;"
                + "border:1px solid #edeef8;box-shadow:0 12px 32px rgba(45,32,120,0.10);overflow:hidden;\">"
                + "<tr><td style=\"height:5px;line-height:5px;font-size:0;"
                + "background-color:#6d3ce0;background:linear-gradient(90deg,#6d3ce0,#4c6ff0);\">&nbsp;</td></tr>"
                + "<tr><td style=\"padding:36px 40px 32px;text-align:center;\">"
                + "<div style=\"width:60px;height:60px;margin:0 auto 24px;border-radius:18px;"
                + "background-color:#6d3ce0;background:linear-gradient(135deg,#6d3ce0,#4c6ff0);color:#ffffff;"
                + "font-size:20px;font-weight:700;line-height:60px;font-family:" + font + ";"
                + "box-shadow:0 8px 20px rgba(76,111,240,0.35);\">CD</div>"
                + "<h1 style=\"margin:0 0 12px;font-size:23px;line-height:1.3;color:#15131f;font-weight:700;"
                + "letter-spacing:-.01em;\">" + (hadExistingPassword ? "Reset" : "Set") + " your password</h1>"
                + "<p style=\"margin:0 0 28px;font-size:14px;line-height:1.6;color:#6b6b80;\">"
                + "We received a request to " + verb + " your Coc Docs Platform password. "
                + "Use the button below to choose " + (hadExistingPassword ? "a new one" : "one") + ".</p>"
                + "<a href=\"" + resetUrl + "\" "
                + "style=\"display:inline-block;padding:15px 40px;border-radius:14px;background-color:#6d3ce0;"
                + "background:linear-gradient(135deg,#6d3ce0,#4c6ff0);color:#ffffff;font-size:15px;font-weight:600;"
                + "text-decoration:none;box-shadow:0 10px 24px rgba(109,60,224,0.30);\">" + buttonLabel + "</a>"
                + "<hr style=\"margin:32px 0 20px;border:none;border-top:1px solid #ececf2;\">"
                + "<p style=\"margin:0;font-size:12px;line-height:1.6;color:#6b6b80;\">"
                + "This link expires in 30 minutes and can only be used once. "
                + "If you didn't ask for this, you can safely ignore this email — your password won't change.</p>"
                + "</td></tr></table>"
                + "<p style=\"margin:24px 0 0;font-size:12px;color:#9b9bb0;text-align:center;\">"
                + "Coc Docs Platform &middot; this is an automated message, please don't reply.</p>"
                + "</td></tr></table>"
                + "</body></html>";
    }
}
