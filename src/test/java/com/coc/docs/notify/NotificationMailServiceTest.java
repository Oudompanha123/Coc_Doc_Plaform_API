package com.coc.docs.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * The sender's decisions: who is disclosed to whom, and what happens when
 * there is nothing to send with or nobody to send to.
 *
 * <p>// req: FR-OPS-001, NFR-SEC-PII
 */
class NotificationMailServiceTest {

    private static final String FROM = "Coc Docs Platform <noreply@example.com>";

    private JavaMailSender sender;
    private NotificationMailService service;

    @BeforeEach
    void setUp() {
        sender = mock(JavaMailSender.class);
        // A real (unconnected) MimeMessage rather than a mock: the assertions
        // are about headers the helper writes, so a mock would only confirm
        // that the test and the code agree about method names.
        when(sender.createMimeMessage()).thenAnswer(i -> new JavaMailSenderImpl().createMimeMessage());
        service = new NotificationMailService(providerOf(sender), FROM);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JavaMailSender> providerOf(JavaMailSender sender) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        return provider;
    }

    private MimeMessage captureSent() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("recipients go in Bcc, so a digest does not publish its readers to each other")
    void bccsRecipients() throws Exception {
        boolean sent = service.sendBcc(
                List.of("a@example.com", "b@example.com"), "Digest", "text", "<p>html</p>");

        assertThat(sent).isTrue();
        MimeMessage message = captureSent();
        assertThat(message.getRecipients(Message.RecipientType.BCC)).hasSize(2);
        // To: is the sender itself — a Bcc-only message is refused or filed as
        // spam by some relays.
        assertThat(message.getRecipients(Message.RecipientType.TO)).hasSize(1);
        assertThat(message.getSubject()).isEqualTo("Digest");
    }

    @Test
    @DisplayName("send() puts recipients in To, for the cases where that is intended")
    void sendUsesToHeader() throws Exception {
        service.send(List.of("a@example.com"), "Direct", "text", "<p>html</p>");

        MimeMessage message = captureSent();
        assertThat(message.getRecipients(Message.RecipientType.TO)).hasSize(1);
        assertThat(message.getRecipients(Message.RecipientType.BCC)).isNull();
    }

    @Test
    @DisplayName("duplicate and blank addresses are dropped")
    void normalisesRecipients() throws Exception {
        service.sendBcc(
                List.of("a@example.com", " A@Example.com ", "", "   ", "b@example.com"),
                "Digest",
                "text",
                "<p>html</p>");

        assertThat(captureSent().getRecipients(Message.RecipientType.BCC)).hasSize(2);
    }

    @Test
    @DisplayName("no recipients is a no-op, not a failure")
    void skipsWhenNoRecipients() {
        assertThat(service.sendBcc(List.of(), "Digest", "text", "<p>html</p>")).isFalse();
        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("no SMTP configured logs instead of failing the caller")
    @SuppressWarnings("unchecked")
    void skipsWhenNoMailSender() {
        ObjectProvider<JavaMailSender> absent = mock(ObjectProvider.class);
        when(absent.getIfAvailable()).thenReturn(null);

        NotificationMailService offline = new NotificationMailService(absent, FROM);

        assertThat(offline.sendBcc(List.of("a@example.com"), "Digest", "text", "<p>html</p>")).isFalse();
    }

    @Test
    @DisplayName("an SMTP failure is reported as false, never thrown at the scheduled job")
    void swallowsSendFailure() {
        doThrow(new MailSendException("relay refused")).when(sender).send(any(MimeMessage.class));

        assertThat(service.sendBcc(List.of("a@example.com"), "Digest", "text", "<p>html</p>")).isFalse();
    }

    @Test
    @DisplayName("both bodies are sent, so a text-preferring client is not left with an empty message")
    void sendsMultipartAlternative() throws Exception {
        service.sendBcc(List.of("a@example.com"), "Digest", "the text part", "<p>the html part</p>");

        // getContentType() is not asserted: the message is captured before the
        // transport calls saveChanges(), and until then the Content-Type header
        // has not been written from the content.
        //
        // The bodies are collected by walking the tree rather than by indexing
        // into it, because MimeMessageHelper's mixed/related/alternative
        // nesting is its business and not this test's — what matters is that
        // both bodies are in the message.
        List<String> bodies = new ArrayList<>();
        collectText(captureSent().getContent(), bodies);

        assertThat(bodies).containsExactlyInAnyOrder("the text part", "<p>the html part</p>");
    }

    /** Collects every text body in the message, at whatever depth it is nested. */
    private static void collectText(Object content, List<String> into) throws Exception {
        if (content instanceof String text) {
            into.add(text);
        } else if (content instanceof MimeMultipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                collectText(multipart.getBodyPart(i).getContent(), into);
            }
        }
    }
}
