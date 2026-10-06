package com.coc.docs.auth;

import com.coc.docs.config.AppProperties;
import com.coc.docs.mail.EmailLayout;
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
    private final EmailLayout layout;
    private final String fromAddress;

    public AccountInviteMailer(
            ObjectProvider<JavaMailSender> mailSender,
            AppProperties properties,
            EmailLayout layout,
            @Value("${app.mail.from:Coc Docs Platform <noreply@cocdocsplatform.com>}") String fromAddress) {
        this.mailSender = mailSender;
        this.properties = properties;
        this.layout = layout;
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
            // Inside the email, so it shows wherever the site runs (ADR-092).
            EmailLayout.embedLogo(helper);
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
     * Rendered through {@link EmailLayout}, which owns the brand mark, card
     * and typography all three mailers share.
     *
     * <p>There is deliberately no copy button beside the password: mail
     * clients strip {@code <script>}, so no control in an email body can
     * ever reach the Clipboard API, and a non-functional icon is worse than
     * none. The caption under the field carries the instruction that does
     * work — select the text — which is the one mechanism every client
     * supports.
     */
    private String htmlBody(String email, String temporaryPassword, String loginUrl) {
        return layout.page(
                "Your account is ready — a temporary password is inside.",
                EmailLayout.heading("Account created")
                        + EmailLayout.lead("A single-use credential is ready for your first login.")
                        + EmailLayout.field("Account email", email, false, EmailLayout.accentBlue())
                        + EmailLayout.spacer(20)
                        + EmailLayout.field("Temporary password", temporaryPassword, true, EmailLayout.accentPurple())
                        + "<p style=\"margin:8px 0 0;font-size:11px;line-height:1.5;color:#9b9bb0;text-align:left;\">"
                        + "Tap or select to copy the password above.</p>"
                        + EmailLayout.reassurance(
                                "For security, please set a new personal password immediately after logging in.")
                        + EmailLayout.button(loginUrl, "Log in")
                        + EmailLayout.footnote("A unique one-time password has been generated for your first "
                                + "login only. If you weren't expecting this, please "
                                + "<strong style=\"color:#4c6ff0;\">contact your administrator</strong> "
                                + "immediately."));
    }
}
