package com.coc.docs.auth;

import com.coc.docs.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends the password-reset email, or — where no SMTP is configured — says so
 * loudly instead (ADR-018 decision 5).
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
 * <p>// req: ADR-018
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
            @Value("${app.mail.from:no-reply@cocdocsplatform.local}") String fromAddress) {
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
     */
    public void sendResetLink(String toEmail, String resetToken) {
        String resetUrl = properties.frontendOrigin() + "/reset-password?token=" + resetToken;

        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.warn(
                    "no SMTP mail sender configured (spring.mail.host unset); password reset link for "
                            + "{} was NOT emailed and is logged here instead — this is acceptable for local "
                            + "development only, never for a production deployment: {}",
                    toEmail,
                    resetUrl);
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject("Reset your Coc Docs Platform password");
        message.setText(
                "We received a request to reset your Coc Docs Platform password.\n\n"
                        + "Reset it here (this link expires in 30 minutes and can be used once):\n"
                        + resetUrl
                        + "\n\n"
                        + "If you didn't request this, you can ignore this email — your password will not change.");
        sender.send(message);
    }
}
