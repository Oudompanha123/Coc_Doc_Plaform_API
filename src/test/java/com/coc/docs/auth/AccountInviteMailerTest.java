package com.coc.docs.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.coc.docs.config.AppProperties;
import com.coc.docs.mail.EmailLayout;
import com.coc.docs.mail.MailParts;
import jakarta.mail.BodyPart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * "Your Account Has Been Created" — its logo travels inside the email
 * (ADR-092). Plain unit test: no Spring context, no SMTP.
 *
 * <p>// req: ADR-092
 */
class AccountInviteMailerTest {

    @Test
    @DisplayName("the logo is embedded, even when the site runs on localhost where Gmail could never fetch it")
    void theLogoIsEmbeddedEvenOnLocalhost() throws Exception {
        // The development setup that showed the broken image.
        AppProperties properties = new AppProperties(
                Duration.ofDays(1), "http://localhost:3000", true, "", "", new AppProperties.Storage("", "", "", "", ""));
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(provider.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(message);
        AccountInviteMailer mailer = new AccountInviteMailer(
                provider, properties, new EmailLayout(properties), "Coc Docs Platform <noreply@example.com>");

        mailer.sendTemporaryPassword("new.person@example.com", "Temp#Passw0rd");
        message.saveChanges();

        String html = MailParts.html(message);
        assertThat(html).contains("src=\"cid:" + EmailLayout.LOGO_CID + "\"");
        assertThat(html).doesNotContain("src=\"http://localhost:3000");
        BodyPart logo = MailParts.logo(message);
        assertThat(logo).isNotNull();
        assertThat(logo.getContentType()).startsWith("image/png");
        assertThat(message.getSubject()).isEqualTo("Your Account Has Been Created");
    }
}
