package com.coc.docs.config;

import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/**
 * Says at startup whether this deployment can actually send email, and from
 * what address.
 *
 * <p><b>Why this exists.</b> Every mailer in this codebase — {@code
 * PasswordResetMailer}, {@code AccountInviteMailer}, {@code
 * RegistrationOtpMailer}, {@code NotificationMailService} — degrades to logging
 * when no {@code JavaMailSender} bean exists, so that a deployment without SMTP
 * still boots and serves every other route. The cost of that choice is that
 * misconfigured mail is <em>silent</em>: nothing fails, nothing warns, and the
 * first sign of trouble is a colleague saying they never got the email. One
 * line at startup turns that into something you can see before anyone waits on
 * a message that is never coming.
 *
 * <p>Never logs the password, and never the full username — only its domain,
 * which is what the From check below needs. A log stream is a wider audience
 * than the people who configured the mailbox.
 *
 * <p>// req: FR-OPS-001, NFR-OPS-AUDIT
 */
@Component
public class MailConfigurationReport {

    private static final Logger log = LoggerFactory.getLogger(MailConfigurationReport.class);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final String fromAddress;

    public MailConfigurationReport(
            ObjectProvider<JavaMailSender> mailSender,
            @Value("${app.mail.from:Coc Docs Platform <noreply@cocdocsplatform.com>}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        JavaMailSender sender = mailSender.getIfAvailable();

        if (sender == null) {
            log.warn(
                    "MAIL: not configured (spring.mail.host is unset). Password resets, account "
                            + "invites, registration codes and the activity digest will be LOGGED, not "
                            + "sent. Fine for local development; see docs/ops/EMAIL-DELIVERY.md before "
                            + "a deployment anyone depends on.");
            return;
        }

        if (!(sender instanceof JavaMailSenderImpl impl)) {
            log.info("MAIL: configured, sending as {}", fromAddress);
            return;
        }

        log.info(
                "MAIL: {}:{}, authenticating as a {} account, sending as {}",
                impl.getHost(),
                impl.getPort(),
                domainOf(impl.getUsername()).orElse("(no username set)"),
                fromAddress);

        senderRewriteWarning(impl.getHost(), impl.getUsername(), fromAddress)
                .ifPresent(log::warn);
    }

    /**
     * Warns when Google will not send as the configured From address.
     *
     * <p>Gmail and the Workspace relay both refuse to let an arbitrary address
     * into {@code From}: mail authenticated as one account is rewritten to that
     * account's address unless the address is a verified alias (Gmail) or sits
     * in a domain the relay is authorised for (Workspace). The default
     * {@code app.mail.from} in this codebase is a placeholder domain nobody
     * owns, so a deployment that configures Gmail and forgets this one property
     * sends mail signed as somebody else — and the reply-to a recipient uses
     * goes nowhere.
     *
     * <p>Static and separate so it is testable without starting a mail server.
     *
     * @return the warning, or empty when nothing looks wrong
     */
    static Optional<String> senderRewriteWarning(String host, String username, String from) {
        if (host == null || username == null) {
            return Optional.empty();
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);
        if (!lowerHost.contains("gmail.com") && !lowerHost.contains("google.com")) {
            return Optional.empty();
        }

        Optional<String> accountDomain = domainOf(username);
        Optional<String> fromDomain = domainOf(from);
        if (accountDomain.isEmpty() || fromDomain.isEmpty()) {
            return Optional.empty();
        }
        if (accountDomain.get().equals(fromDomain.get())) {
            return Optional.empty();
        }

        return Optional.of(
                "MAIL: app.mail.from is an address at '" + fromDomain.get() + "' but this server "
                        + "authenticates to Google as a '" + accountDomain.get() + "' account. Google "
                        + "rewrites From to the authenticated account unless the address is a verified "
                        + "alias (Gmail) or a domain the relay is authorised for (Workspace), so "
                        + "recipients will not see the address you configured. See "
                        + "docs/ops/EMAIL-DELIVERY.md.");
    }

    /**
     * The domain of an address, whether bare or in {@code Name <a@b>} form.
     *
     * <p>Lower-cased: the domain half of an address is case-insensitive, and a
     * check that said {@code Gmail.com} differs from {@code gmail.com} would
     * warn about a configuration that is perfectly correct.
     */
    private static Optional<String> domainOf(String address) {
        if (address == null || address.isBlank()) {
            return Optional.empty();
        }
        String trimmed = address.trim();
        int open = trimmed.indexOf('<');
        int close = trimmed.indexOf('>');
        if (open >= 0 && close > open) {
            trimmed = trimmed.substring(open + 1, close).trim();
        }
        int at = trimmed.lastIndexOf('@');
        if (at < 0 || at == trimmed.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(trimmed.substring(at + 1).toLowerCase(Locale.ROOT));
    }
}
