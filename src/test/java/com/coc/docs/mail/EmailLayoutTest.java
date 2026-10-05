package com.coc.docs.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.coc.docs.config.AppProperties;
import jakarta.mail.BodyPart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * The shared email chrome. Plain unit test — no Spring context, no database,
 * no Docker — so it runs everywhere.
 *
 * <p>The assertions here are narrow on purpose. Testing rendered HTML by
 * string comparison is how a template becomes untouchable, so nothing below
 * asserts a colour, a radius or a font size; restyling an email must not
 * fail a test. What is pinned is only what breaks silently and in somebody
 * else's inbox: the logo being embedded (and actually attached), and the
 * escaping.
 *
 * <p>// req: ADR-018, ADR-021
 */
class EmailLayoutTest {

    private static final String ORIGIN = "https://docs.example.com";

    private final EmailLayout layout = new EmailLayout(new AppProperties(
            Duration.ofDays(1), ORIGIN, true, "", "", new AppProperties.Storage("", "", "", "", "")));

    @Test
    @DisplayName("the brand mark is the embedded logo, not a link a mail client has to fetch (ADR-092)")
    void logoIsEmbeddedNotLinked() {
        String html = layout.page("preview", EmailLayout.heading("Hello"));

        // A linked logo only loads where the mail client can reach the
        // frontend origin — Gmail's image proxy cannot reach localhost.
        assertThat(html).contains("src=\"cid:" + EmailLayout.LOGO_CID + "\"");
        assertThat(html).doesNotContain("src=\"" + ORIGIN);
        assertThat(html).doesNotContain("src=\"/logo");
        // Both dimensions, or Outlook reserves no space and the card jumps
        // as the image loads.
        assertThat(html).contains("width=\"168\"").contains("height=\"80\"");
        assertThat(html).contains("alt=\"Coc Docs Platform\"");
        // The link around it still goes to the site.
        assertThat(html).contains("href=\"" + ORIGIN + "\"");
    }

    @Test
    @DisplayName("embedLogo attaches the logo as an inline image under the Content-ID the page points at")
    void embedLogoAttachesTheInlineImage() throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setText("plain", layout.page("preview", ""));

        EmailLayout.embedLogo(helper);
        message.saveChanges();

        BodyPart logo = MailParts.logo(message);
        assertThat(logo).as("inline part with Content-ID <%s>", EmailLayout.LOGO_CID).isNotNull();
        assertThat(logo.getContentType()).startsWith("image/png");
        assertThat(logo.getDisposition()).isEqualTo(BodyPart.INLINE);
        // A real PNG, not an empty part: the resource must exist in the jar.
        byte[] bytes = logo.getInputStream().readAllBytes();
        assertThat(bytes).hasSizeGreaterThan(1000);
        assertThat(java.util.Arrays.copyOf(bytes, 4)).containsExactly(0x89, 'P', 'N', 'G');
    }

    @Test
    @DisplayName("the preheader is present and hidden")
    void preheaderIsHidden() {
        String html = layout.page("Your verification code is 123456", "");

        assertThat(html).contains("Your verification code is 123456");
        assertThat(html).contains("display:none").contains("mso-hide:all");
    }

    @Test
    @DisplayName("interpolated text is escaped, so a value can never open a tag")
    void interpolatedTextIsEscaped() {
        String html = layout.page(
                "preview",
                EmailLayout.heading("<script>alert(1)</script>")
                        + EmailLayout.field("Account email", "a\"b<c@example.com", false, EmailLayout.accentBlue()));

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).contains("a&quot;b&lt;c@example.com");
    }

    @Test
    @DisplayName("the button keeps a solid background behind the gradient, for clients that ignore gradients")
    void buttonHasASolidFallback() {
        String html = EmailLayout.button("https://example.com/reset", "Reset password");

        // Outlook drops `background:linear-gradient(...)` entirely. Without
        // the preceding background-color the label is white on white.
        assertThat(html.indexOf("background-color:")).isLessThan(html.indexOf("linear-gradient"));
        assertThat(html).contains("href=\"https://example.com/reset\"");
    }
}
