package com.coc.docs.notify;

import java.util.Properties;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/**
 * Builds a one-off {@link JavaMailSender} that authenticates as one person
 * (ADR-063), rather than as the shared {@code spring.mail.*} account.
 *
 * <p>Hand-built because Spring autoconfigures exactly one sender from one set
 * of properties, and this needs a different identity per message. The host and
 * port are taken from the shared configuration — everybody here is on the same
 * relay; only the credential differs — so a deployment that moves off Gmail
 * moves this with it and not separately.
 *
 * <p><b>Not cached.</b> A sender per send is cheap next to opening an SMTP
 * connection, and a cache keyed by account would hold decrypted App Passwords
 * in memory for the life of the process. They live for the duration of one
 * send instead.
 *
 * <p>// req: NFR-SEC-PII, NFR-SEC-CHANNEL, ADR-063
 */
@Component
public class PersonalMailSenderFactory {

    private final String host;
    private final int port;

    public PersonalMailSenderFactory(
            @org.springframework.beans.factory.annotation.Value("${spring.mail.host:}") String host,
            @org.springframework.beans.factory.annotation.Value("${spring.mail.port:587}") int port) {
        this.host = host;
        this.port = port;
    }

    /** Whether a per-account sender can be built at all — false with no relay configured. */
    public boolean isUsable() {
        return host != null && !host.isBlank();
    }

    /**
     * A sender authenticating as this credential's owner.
     *
     * <p>Returns empty for an OAuth credential whose access token could not be
     * minted — a revoked or expired grant (ADR-085 §4.2). The caller falls
     * back to the shared sender rather than dropping the mail, which is the
     * same contract a stale App Password has.
     */
    public java.util.Optional<JavaMailSender> senderOrEmpty(
            AccountMailCredentialRepository.Credential credential, GoogleMailOAuthService oauth) {
        if (!credential.isOauth()) {
            return java.util.Optional.of(senderFor(credential));
        }
        return oauth.accessTokenFor(credential.accountId(), credential.oauthRefreshToken())
                .map(token -> build(credential.username(), token, true));
    }

    public JavaMailSender senderFor(AccountMailCredentialRepository.Credential credential) {
        return build(credential.username(), credential.appPassword(), false);
    }

    /**
     * @param secret an App Password, or an OAuth access token when
     *     {@code xoauth2} is set. Jakarta Mail carries both in the same slot:
     *     with {@code mail.smtp.auth.mechanisms=XOAUTH2} the "password" is sent
     *     as the bearer token instead of in a PLAIN exchange, so the only
     *     difference between the two paths is which mechanism is advertised.
     */
    private JavaMailSender build(String username, String secret, boolean xoauth2) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(username);
        sender.setPassword(secret);
        sender.setDefaultEncoding("UTF-8");

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        if (xoauth2) {
            // Named explicitly rather than left to negotiation: Gmail offers
            // PLAIN as well, and a client that picks it sends the access token
            // as if it were a password, which Google refuses. The failure then
            // reads as a bad credential rather than a wrong mechanism.
            props.put("mail.smtp.auth.mechanisms", "XOAUTH2");
        }
        // STARTTLS on 587, which is what Gmail expects and what the shared
        // configuration already uses. Explicit rather than inherited so that a
        // relay change cannot quietly downgrade this path alone.
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");
        return sender;
    }
}
