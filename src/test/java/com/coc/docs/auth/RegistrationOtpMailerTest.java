package com.coc.docs.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coc.docs.config.AppProperties;
import com.coc.docs.mail.EmailLayout;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * A wrong or unreachable SMTP relay must never fail the {@code /register}
 * (or {@code /register/resend}) request it's called from — plain unit test,
 * no Spring context, no database, no Docker, so it runs everywhere.
 *
 * <p>This is exactly the class of bug that shipped once already: {@code
 * sender.send()} throws an <em>unchecked</em> {@code MailException} subtype
 * on a real transport failure, and the method's original {@code catch}
 * clause only caught the checked {@code MessagingException} thrown while
 * building the message — leaving a real SMTP failure (wrong credentials,
 * unreachable host, the relay rejecting the sender address) to propagate
 * uncaught out of {@code RegistrationOtpService.issue()}, failing the whole
 * request with a raw 500 instead of the caller ever seeing the "check your
 * email" response.
 *
 * <p>// req: ADR-021
 */
class RegistrationOtpMailerTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);

    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final RegistrationOtpMailer mailer =
            new RegistrationOtpMailer(provider, layout(), "Coc Docs Platform <noreply@example.com>");

    /** The layout only needs a frontend origin, to build the logo URL from. */
    private static EmailLayout layout() {
        return new EmailLayout(new AppProperties(
                Duration.ofDays(1),
                "https://docs.example.com",
                true,
                "",
                    "test-client-secret",
                new AppProperties.Storage("", "", "", "", "")));
    }

    /** A real, if disconnected, message -- {@code MimeMessageHelper} needs a genuine {@code Session}, not a bare mock. */
    private static MimeMessage newMessage() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    @Test
    @DisplayName("logs and returns, never throws, when no SMTP sender is configured")
    void noSenderConfigured() {
        when(provider.getIfAvailable()).thenReturn(null);

        assertThatCode(() -> mailer.sendCode("someone@example.com", "123456", Duration.ofMinutes(10)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a real SMTP transport failure (auth, connection, relay refusal) is caught, not propagated")
    void transportFailureIsCaughtNotPropagated() {
        when(provider.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(newMessage());
        // Gmail's own SMTP (and most real relays) reject a plain account
        // password and require an app password -- this is the exact
        // exception shape that failure takes.
        doThrow(new MailAuthenticationException("535 Authentication failed"))
                .when(sender)
                .send(any(MimeMessage.class));

        assertThatCode(() -> mailer.sendCode("someone@example.com", "123456", Duration.ofMinutes(10)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("sends through the real sender when one is available")
    void sendsWhenConfigured() {
        when(provider.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(newMessage());

        mailer.sendCode("someone@example.com", "123456", Duration.ofMinutes(10));

        verify(sender).send(any(MimeMessage.class));
    }
}
