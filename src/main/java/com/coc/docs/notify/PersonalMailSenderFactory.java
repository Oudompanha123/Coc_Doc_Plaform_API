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

    public JavaMailSender senderFor(AccountMailCredentialRepository.Credential credential) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(credential.username());
        sender.setPassword(credential.appPassword());
        sender.setDefaultEncoding("UTF-8");

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
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
