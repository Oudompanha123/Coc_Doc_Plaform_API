package com.coc.docs.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.coc.docs.config.AppProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shared email chrome. Plain unit test — no Spring context, no database,
 * no Docker — so it runs everywhere.
 *
 * <p>The assertions here are narrow on purpose. Testing rendered HTML by
 * string comparison is how a template becomes untouchable, so nothing below
 * asserts a colour, a radius or a font size; restyling an email must not
 * fail a test. What is pinned is only what breaks silently and in somebody
 * else's inbox: the logo filename matching a real file in the frontend's
 * {@code public/} directory, the origin it is built from, and the escaping.
 *
 * <p>// req: ADR-018, ADR-021
 */
class EmailLayoutTest {

    private static final String ORIGIN = "https://docs.example.com";

    private final EmailLayout layout = new EmailLayout(new AppProperties(
            Duration.ofDays(1), ORIGIN, true, "", "", new AppProperties.Storage("", "", "", "", "")));

    @Test
    @DisplayName("the brand mark is the real logo, served from the configured frontend origin")
    void logoComesFromTheFrontendOrigin() {
        String html = layout.page("preview", EmailLayout.heading("Hello"));

        // frontend/public/logo-coc-docs-platform.png -- if that file is ever
        // renamed, every email quietly falls back to alt text and nobody
        // finds out from the application itself.
        assertThat(html).contains("src=\"" + ORIGIN + "/logo-coc-docs-platform.png\"");
        // Both dimensions, or Outlook reserves no space and the card jumps
        // as the image loads.
        assertThat(html).contains("width=\"168\"").contains("height=\"80\"");
        assertThat(html).contains("alt=\"Coc Docs Platform\"");
    }

    @Test
    @DisplayName("a relative logo path is never emitted — a mail client has no base URL to resolve one against")
    void logoIsAlwaysAbsolute() {
        String html = layout.page("preview", "");

        assertThat(html).doesNotContain("src=\"/logo");
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
