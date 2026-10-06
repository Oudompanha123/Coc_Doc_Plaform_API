package com.coc.docs.auth;

import com.coc.docs.account.AppRole;
import com.coc.docs.account.RegistrationDecidedEvent;
import com.coc.docs.config.AppProperties;
import com.coc.docs.mail.EmailLayout;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Emails an applicant the outcome of their registration (ADR-096): approved
 * — with the role granted and a link to log in — or not approved, with the
 * news that they may register again.
 *
 * <p>{@code AFTER_COMMIT}, because a decision that rolled back decided
 * nothing; {@code @Async}, so a slow or unreachable mail relay never sits
 * inside the administrator's Approve or Refuse request. Every failure is
 * logged and swallowed, exactly as {@link AccountInviteMailer} does: the
 * decision stands whether or not the email gets through.
 *
 * <p>No SMTP configured (local development): the outcome is logged instead,
 * with nothing secret in it — there is no credential in either email.
 *
 * <p>// req: FR-AUTH-002, ADR-017, ADR-096
 */
@Component
public class RegistrationDecisionMailer {

    private static final Logger log = LoggerFactory.getLogger(RegistrationDecisionMailer.class);

    /** The names people see for each role, as in Users & Roles. */
    private static final Map<AppRole, String> ROLE_NAMES = Map.of(
            AppRole.USER, "User",
            AppRole.DEVELOPER, "Developer",
            AppRole.REVIEWER, "Reviewer",
            AppRole.LEAD, "Lead",
            AppRole.ADMIN, "Admin",
            AppRole.SUPER_ADMIN, "Super Admin");

    private final ObjectProvider<JavaMailSender> mailSender;
    private final AppProperties properties;
    private final EmailLayout layout;
    private final String fromAddress;

    public RegistrationDecisionMailer(
            ObjectProvider<JavaMailSender> mailSender,
            AppProperties properties,
            EmailLayout layout,
            @Value("${app.mail.from:Coc Docs Platform <noreply@cocdocsplatform.com>}") String fromAddress) {
        this.mailSender = mailSender;
        this.properties = properties;
        this.layout = layout;
        this.fromAddress = fromAddress;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRegistrationDecided(RegistrationDecidedEvent event) {
        if (event.approved()) {
            sendApproved(event.email(), event.displayName(), event.role());
        } else {
            sendRejected(event.email(), event.displayName());
        }
    }

    /** "Your account has been approved" — the role, and a link straight to log in. */
    public void sendApproved(String toEmail, String displayName, AppRole role) {
        String loginUrl = properties.frontendOrigin() + "/login?email=" + URLEncoder.encode(toEmail, StandardCharsets.UTF_8);
        String roleName = ROLE_NAMES.getOrDefault(role, "User");
        String greeting = greeting(displayName);
        send(
                toEmail,
                "Your account has been approved",
                greeting + "\n\nYour registration for Coc Docs Platform has been approved. You can log in now.\n\n"
                        + "Account email: " + toEmail + "\n"
                        + "Role: " + roleName + "\n\n"
                        + "Log in: " + loginUrl + "\n",
                layout.page(
                        "Good news — your account has been approved. You can log in now.",
                        EmailLayout.heading("Your account has been approved")
                                + EmailLayout.lead(greeting + " Your registration for Coc Docs Platform has been "
                                        + "approved by an administrator. You can log in now.")
                                + EmailLayout.field("Account email", toEmail, false, EmailLayout.accentBlue())
                                + EmailLayout.spacer(20)
                                + EmailLayout.field("Role", roleName, false, EmailLayout.accentPurple())
                                + EmailLayout.reassurance("Sign in with the password you chose when you registered.")
                                + EmailLayout.button(loginUrl, "Log in")
                                + EmailLayout.footnote("If you didn't register for Coc Docs Platform, please "
                                        + "<strong style=\"color:#4c6ff0;\">contact your administrator</strong>.")));
    }

    /** "Your registration was not approved" — and that they may register again. */
    public void sendRejected(String toEmail, String displayName) {
        String registerUrl = properties.frontendOrigin() + "/register";
        String greeting = greeting(displayName);
        send(
                toEmail,
                "Your registration was not approved",
                greeting + "\n\nYour registration for Coc Docs Platform was not approved by an administrator, "
                        + "so this account cannot sign in.\n\n"
                        + "If you think this was a mistake, you can register again with the same email address, "
                        + "or contact your administrator.\n\n"
                        + "Register again: " + registerUrl + "\n",
                layout.page(
                        "Your registration was not approved. You can register again with the same email.",
                        EmailLayout.heading("Your registration was not approved")
                                + EmailLayout.lead(greeting + " Your registration for Coc Docs Platform was not "
                                        + "approved by an administrator, so this account cannot sign in.")
                                + EmailLayout.field("Account email", toEmail, false, EmailLayout.accentBlue())
                                + EmailLayout.reassurance("If you think this was a mistake, you can register "
                                        + "again with the same email address.")
                                + EmailLayout.button(registerUrl, "Register again")
                                + EmailLayout.footnote("Questions about this decision? Please "
                                        + "<strong style=\"color:#4c6ff0;\">contact your administrator</strong>.")));
    }

    private static String greeting(String displayName) {
        return displayName == null || displayName.isBlank() ? "Hello," : "Hello " + displayName.trim() + ",";
    }

    private void send(String toEmail, String subject, String plainText, String html) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.warn(
                    "no SMTP mail sender configured (spring.mail.host unset); the \"{}\" email to {} was NOT sent",
                    subject,
                    toEmail);
            return;
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(plainText, html);
            // Inside the email, so it shows wherever the site runs (ADR-092).
            EmailLayout.embedLogo(helper);
            sender.send(message);
        } catch (MessagingException e) {
            log.error("failed to build the \"{}\" email for {}", subject, toEmail, e);
        } catch (MailException e) {
            // The decision is already saved; an SMTP problem must not undo it.
            log.error("could not send the \"{}\" email to {} — SMTP send failed", subject, toEmail, e);
        }
    }
}
