package com.coc.docs.auth;

import com.coc.docs.config.AppProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Sends the temporary password a {@code super_admin}'s "Add User" issues, or
 * — where no SMTP is configured — says so loudly instead. Same shape as
 * {@link PasswordResetMailer} and for the same reason (ADR-018 decision 5):
 * {@code JavaMailSender} is taken through an {@link ObjectProvider} so a
 * deployment without SMTP configured still boots and serves every other
 * route exactly as it does today.
 *
 * <p><b>The logged-password fallback is a local-development convenience, not
 * a production behaviour</b> — stated as plainly as {@link PasswordResetMailer}
 * states its own version of this gap: a deployment that reaches this branch
 * is writing a live credential to its log stream. Configuring
 * {@code spring.mail.*} before going live is required follow-up this class
 * cannot complete on its own — {@code application.yml} is off-limits to the
 * AI that wrote this class, per the project's own {@code SEC-001}/{@code
 * SEC-003} rules.
 *
 * <p>// req: FR-AUTH-002, FR-AUTH-003, ADR-014, ADR-017
 */
@Component
public class AccountInviteMailer {

    private static final Logger log = LoggerFactory.getLogger(AccountInviteMailer.class);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final AppProperties properties;
    private final String fromAddress;

    public AccountInviteMailer(
            ObjectProvider<JavaMailSender> mailSender,
            AppProperties properties,
            @Value("${app.mail.from:Coc Docs Platform <noreply@cocdocsplatform.com>}") String fromAddress) {
        this.mailSender = mailSender;
        this.properties = properties;
        this.fromAddress = fromAddress;
    }

    /**
     * Emails the plaintext temporary password and the login URL, or logs both
     * if there is nowhere to send them.
     *
     * <p>Takes the raw password, not its hash — this is the one place the
     * plaintext temporary password exists outside the moment it was
     * generated, and it leaves this method only inside the email body, never
     * logged in production (see class Javadoc) and never returned to any
     * caller of this class or any HTTP response.
     */
    public void sendTemporaryPassword(String toEmail, String temporaryPassword) {
        // Carries the email as a query param so the login page can prefill it —
        // the invited user only has to type the temporary password, not
        // retype the address the invite was already sent to.
        String encodedEmail = URLEncoder.encode(toEmail, StandardCharsets.UTF_8);
        String loginUrl = properties.frontendOrigin() + "/login?email=" + encodedEmail;

        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.warn(
                    "no SMTP mail sender configured (spring.mail.host unset); the temporary password "
                            + "for {} was NOT emailed and is logged here instead — this is acceptable for "
                            + "local development only, never for a production deployment: {}",
                    toEmail,
                    temporaryPassword);
            return;
        }

        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(toEmail);
            helper.setSubject("Your Account Has Been Created");
            helper.setText(plainTextBody(toEmail, temporaryPassword, loginUrl), htmlBody(toEmail, temporaryPassword, loginUrl));
            sender.send(message);
        } catch (MessagingException e) {
            // A malformed address or similar — not a caller-facing concern, the
            // same reasoning as PasswordResetMailer — but it must not vanish
            // silently either.
            log.error("failed to build account invite email for {}", toEmail, e);
        } catch (MailException e) {
            // The transport itself refused it — wrong host, auth failure,
            // connection refused, the sender address rejected by the relay.
            // Unlike MessagingException (thrown while building the message),
            // this is thrown by sender.send() itself, and account creation
            // must not be undone by an SMTP problem: the account is still
            // usable, an administrator can still see it was invited, and this
            // is the one place that failure is visible at all.
            log.error("could not send the account invite email to {} — SMTP send failed", toEmail, e);
        }
    }

    private static String plainTextBody(String email, String temporaryPassword, String loginUrl) {
        return "ACCOUNT CREATED\n\n"
                + "A single-use credential is ready for your first login.\n\n"
                + "Account email: " + email + "\n"
                + "Temporary password: " + temporaryPassword + "\n\n"
                + "For security, please set a new personal password immediately after logging in.\n\n"
                + "Log in: " + loginUrl + "\n\n"
                + "This is a one-time password generated for your first login only. If you weren't "
                + "expecting this, please contact your administrator immediately.";
    }

    /**
     * Inline-styled, matching {@link PasswordResetMailer}'s reasoning: a
     * {@code <style>} block is stripped by enough mail clients that it cannot
     * be relied on, and there is no asset host to point an {@code <img>} at
     * (nor consistent-enough inline-SVG support). There is deliberately no
     * copy icon or button next to the password — mail clients strip
     * {@code <script>}, so no control in an email body can ever call the
     * Clipboard API, in this template or any other, and a non-functional
     * icon next to the field is worse than none. The caption below the
     * password field carries the actual instruction: select the text to
     * copy it, which is the one mechanism every mail client actually
     * supports.
     */
    private static String htmlBody(String email, String temporaryPassword, String loginUrl) {
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
                + "<h1 style=\"margin:0 0 10px;font-size:22px;line-height:1.3;color:#15131f;font-weight:800;"
                + "letter-spacing:.02em;text-transform:uppercase;\">Account Created</h1>"
                + "<p style=\"margin:0 0 28px;font-size:14px;line-height:1.6;color:#6b6b80;text-align:left;\">"
                + "A single-use credential is ready for your first login.</p>"
                + "<p style=\"margin:0 0 6px;font-size:11px;font-weight:700;color:#8b8ba7;text-align:left;"
                + "letter-spacing:.06em;text-transform:uppercase;\">Account email</p>"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"margin:0 0 20px;border-radius:12px;background-color:#f7f7fc;\">"
                + "<tr>"
                + "<td width=\"36\" style=\"padding:14px 0 14px 18px;border-left:3px solid #4c6ff0;"
                + "border-radius:12px 0 0 12px;color:#4c6ff0;font-size:14px;\">&#128274;</td>"
                + "<td style=\"padding:14px 18px 14px 0;border-radius:0 12px 12px 0;"
                + "font-size:14px;font-weight:600;color:#15131f;text-align:left;\">"
                + email + "</td></tr></table>"
                + "<p style=\"margin:0 0 6px;font-size:11px;font-weight:700;color:#8b8ba7;text-align:left;"
                + "letter-spacing:.06em;text-transform:uppercase;\">Temporary password</p>"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"margin:0;border-radius:12px;background-color:#f7f7fc;\">"
                + "<tr>"
                + "<td style=\"padding:14px 18px;border-left:3px solid #6d3ce0;border-radius:12px;"
                + "font-family:'SFMono-Regular','Menlo','Consolas',monospace;"
                + "font-size:19px;font-weight:700;color:#15131f;letter-spacing:.05em;text-align:left;\">"
                + temporaryPassword + "</td>"
                + "</tr></table>"
                + "<p style=\"margin:8px 0 28px;font-size:11px;line-height:1.5;color:#9b9bb0;text-align:left;\">"
                + "Tap or select to copy the password above.</p>"
                + "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:0 0 28px;\">"
                + "<tr>"
                + "<td width=\"20\" valign=\"top\" style=\"padding-right:8px;\">"
                + "<span style=\"display:inline-block;width:16px;height:16px;line-height:16px;border-radius:50%;"
                + "background-color:#1f9d6b;color:#ffffff;font-size:10px;font-weight:700;text-align:center;\">"
                + "&#10003;</span></td>"
                + "<td style=\"font-size:14px;line-height:1.6;color:#6b6b80;text-align:left;\">"
                + "For security, please set a new personal password immediately after logging in.</td>"
                + "</tr></table>"
                + "<a href=\"" + loginUrl + "\" "
                + "style=\"display:inline-block;padding:15px 40px;border-radius:14px;background-color:#6d3ce0;"
                + "background:linear-gradient(135deg,#6d3ce0,#4c6ff0);color:#ffffff;font-size:15px;font-weight:600;"
                + "text-decoration:none;box-shadow:0 10px 24px rgba(109,60,224,0.30);\">Log in</a>"
                + "<hr style=\"margin:32px 0 20px;border:none;border-top:1px solid #ececf2;\">"
                + "<p style=\"margin:0;font-size:12px;line-height:1.6;color:#6b6b80;text-align:left;\">"
                + "A unique one-time password has been generated for your first login only. If you "
                + "weren't expecting this, please <strong style=\"color:#4c6ff0;\">contact your "
                + "administrator</strong> immediately.</p>"
                + "</td></tr></table>"
                + "<p style=\"margin:24px 0 0;font-size:12px;color:#9b9bb0;text-align:center;\">"
                + "Coc Docs Platform &middot; this is an automated message, please don't reply.</p>"
                + "</td></tr></table>"
                + "</body></html>";
    }
}
