package com.coc.docs.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.coc.docs.account.AppRole;
import com.coc.docs.account.RegistrationDecidedEvent;
import com.coc.docs.config.AppProperties;
import com.coc.docs.mail.EmailLayout;
import com.coc.docs.mail.MailParts;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The applicant hears the outcome of their registration (ADR-096). Plain unit
 * test: no Spring context, no SMTP.
 *
 * <p>// req: FR-AUTH-002, ADR-096
 */
class RegistrationDecisionMailerTest {

    private final AppProperties properties = new AppProperties(
            Duration.ofDays(1), "https://docs.example.com", true, "", "", new AppProperties.Storage("", "", "", "", ""));
    private JavaMailSender sender;
    private MimeMessage message;
    private RegistrationDecisionMailer mailer;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        sender = mock(JavaMailSender.class);
        message = new MimeMessage(Session.getInstance(new Properties()));
        when(provider.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(message);
        mailer = new RegistrationDecisionMailer(
                provider, properties, new EmailLayout(properties), "Coc Docs Platform <noreply@example.com>");
    }

    @Test
    @DisplayName("approved: says so, names the role granted, links to log in, logo embedded")
    void approvedEmail() throws Exception {
        mailer.onRegistrationDecided(new RegistrationDecidedEvent(
                UUID.randomUUID(), "new.person@example.com", "New Person", true, AppRole.REVIEWER));
        message.saveChanges();

        assertThat(message.getSubject()).isEqualTo("Your account has been approved");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("new.person@example.com");
        assertThat(MailParts.html(message))
                .contains("Hello New Person,")
                .contains("Reviewer")
                .contains("https://docs.example.com/login?email=new.person%40example.com")
                .contains("src=\"cid:" + EmailLayout.LOGO_CID + "\"");
        assertThat(MailParts.logo(message)).isNotNull();
    }

    @Test
    @DisplayName("refused: says it was not approved, and that they may register again")
    void rejectedEmail() throws Exception {
        mailer.onRegistrationDecided(new RegistrationDecidedEvent(
                UUID.randomUUID(), "new.person@example.com", "New Person", false, null));
        message.saveChanges();

        assertThat(message.getSubject()).isEqualTo("Your registration was not approved");
        assertThat(MailParts.html(message))
                .contains("was not")
                .contains("register again with the same email address")
                .contains("https://docs.example.com/register");
        assertThat(MailParts.logo(message)).isNotNull();
    }

    @Test
    @DisplayName("an SMTP failure is swallowed: the decision already stands")
    void smtpFailureDoesNotPropagate() {
        doThrow(new MailSendException("relay down")).when(sender).send(message);

        assertThatCode(() -> mailer.sendRejected("new.person@example.com", "New Person"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("sent only after the decision commits, and off the administrator's request")
    void afterCommitAndAsync() throws Exception {
        Method handler =
                RegistrationDecisionMailer.class.getMethod("onRegistrationDecided", RegistrationDecidedEvent.class);

        assertThat(handler.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(handler.getAnnotation(Async.class)).isNotNull();
    }
}
