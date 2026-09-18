package com.coc.docs.mail;

import com.coc.docs.config.AppProperties;
import org.springframework.stereotype.Component;

/**
 * The shared chrome every outgoing email is rendered into — brand mark,
 * card, typography, footer — so the three mailers carry only the part that
 * differs between them.
 *
 * <p><b>Why this exists.</b> {@code PasswordResetMailer}, {@code
 * AccountInviteMailer} and {@code RegistrationOtpMailer} each held their own
 * copy of the same ~40 lines of table markup, and the copies had already
 * drifted: two different page backgrounds ({@code #f1f2fa} and {@code
 * #eef0f8}), two card radii, two badge sizes, three font stacks, and a
 * footer separated by an em dash in one and a middot in the others. None of
 * that was decided; it accumulated. A recipient who gets an invite and then
 * a reset sees two different products.
 *
 * <h2>The brand mark is now the real logo</h2>
 *
 * <p>Each mailer previously drew a gradient tile with the letters "CD" in
 * it, and each said why in its own Javadoc: <em>"there is no asset host to
 * point an {@code <img>} at"</em>. That is no longer true — the frontend
 * serves {@code /logo-coc-docs-platform.png} at {@link
 * AppProperties#frontendOrigin()}, which is a public HTTPS origin in any
 * real deployment, so the emails now use the same logo the application
 * header does.
 *
 * <p>The consequence to know about: <b>the logo only renders where {@code
 * app.frontend-origin} is reachable from the recipient's mail client.</b>
 * Against a local {@code http://localhost:3000} it silently falls back to
 * its alt text, which is the correct outcome and needs no special case —
 * but it is the same misconfiguration that makes every emailed link
 * unclickable, so a missing logo in a real inbox is a useful early symptom
 * of it rather than a styling bug.
 *
 * <p>Gmail fetches images through its own proxy and caches them, so the
 * file must be publicly readable with no session — it is, being served from
 * the Next.js {@code public/} directory ahead of any auth check.
 *
 * <h2>Typography</h2>
 *
 * <p>The application uses Inter ({@code app/layout.tsx}), so these emails
 * ask for Inter first and fall back through the system stack. The webfont
 * {@code <link>} is honoured by Apple Mail and iOS Mail and ignored by
 * Gmail and Outlook, which is why the fallback stack is chosen to be
 * metrically close rather than treated as a last resort — most recipients
 * will read the fallback, not Inter.
 *
 * <p>Everything else stays inline. A {@code <style>} block is stripped by
 * enough clients (Outlook desktop chief among them) that it cannot carry
 * anything load-bearing, which is the same reasoning each mailer gave
 * before and the reason the font link is the only thing in {@code <head>}
 * that matters visually.
 *
 * <p>// req: ADR-018, ADR-021, ADR-029
 */
@Component
public class EmailLayout {

    /** Page background, behind the card. */
    private static final String CANVAS = "#f1f2fa";
    /** Body copy. Darker than the #6b6b80 two of the three templates used —
     *  that sat just above the 4.5:1 floor on white, and email is read in
     *  worse conditions than a monitor at a desk. */
    private static final String INK_BODY = "#5c5c72";
    private static final String INK_HEADING = "#15131f";
    private static final String INK_FOOTER = "#9b9bb0";
    private static final String HAIRLINE = "#ececf2";
    private static final String BRAND_PURPLE = "#6d3ce0";
    private static final String BRAND_BLUE = "#4c6ff0";

    /**
     * Inter first, then the closest system faces. Ordered so that each
     * platform reaches its own UI font before any generic: Segoe UI Variable
     * on Windows 11, Roboto on Android, Helvetica Neue on older macOS.
     */
    private static final String FONT_STACK = "'Inter',-apple-system,BlinkMacSystemFont,"
            + "'Segoe UI Variable','Segoe UI',Roboto,'Helvetica Neue',Helvetica,Arial,sans-serif";

    /** Digits and passwords, where a zero must not read as an O. */
    private static final String FONT_MONO = "'SFMono-Regular',Menlo,Consolas,'Liberation Mono',monospace";

    /** 660x316 in the source file; 168 wide keeps it sharp on a 2x display
     *  without dominating a 480px card. Both dimensions are stated because
     *  Outlook reserves no space for an image that has not declared them,
     *  and the layout jumps as it loads. */
    private static final int LOGO_WIDTH = 168;
    private static final int LOGO_HEIGHT = 80;

    private final AppProperties properties;

    public EmailLayout(AppProperties properties) {
        this.properties = properties;
    }

    /**
     * Wraps {@code contentHtml} in the full page.
     *
     * @param preheader the one line most clients show next to the subject in
     *     the inbox list. Left to chance it becomes whatever text comes
     *     first — for the invite email that was the words "Account created"
     *     twice over. Passed explicitly so each email chooses it.
     * @param contentHtml the body of the card, built from the helpers below
     */
    public String page(String preheader, String contentHtml) {
        return "<!doctype html>"
                + "<html lang=\"en\"><head>"
                + "<meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                // Without these a dark-mode client is free to re-colour the
                // card and the brand bar to whatever it thinks is right,
                // which on a gradient means banding and unreadable ink.
                + "<meta name=\"color-scheme\" content=\"light\">"
                + "<meta name=\"supported-color-schemes\" content=\"light\">"
                + "<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">"
                + "<link href=\"https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap\" "
                + "rel=\"stylesheet\">"
                + "</head>"
                + "<body style=\"margin:0;padding:40px 16px;background-color:" + CANVAS + ";"
                + "font-family:" + FONT_STACK + ";-webkit-font-smoothing:antialiased;"
                + "-moz-osx-font-smoothing:grayscale;\">"
                + preheader(preheader)
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\">"
                + "<tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"480\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
                + "style=\"max-width:480px;width:100%;background-color:#ffffff;border-radius:20px;"
                + "border:1px solid #edeef8;box-shadow:0 12px 32px rgba(45,32,120,0.10);overflow:hidden;\">"
                + "<tr><td style=\"height:5px;line-height:5px;font-size:0;background-color:" + BRAND_PURPLE + ";"
                + "background:linear-gradient(90deg," + BRAND_PURPLE + "," + BRAND_BLUE + ");\">&nbsp;</td></tr>"
                + "<tr><td style=\"padding:36px 40px 32px;text-align:center;\">"
                + logo()
                + contentHtml
                + "</td></tr></table>"
                + "<p style=\"margin:24px 0 0;font-size:12px;line-height:1.6;color:" + INK_FOOTER + ";"
                + "text-align:center;\">Coc Docs Platform &middot; this is an automated message, "
                + "please don't reply.</p>"
                + "</td></tr></table>"
                + "</body></html>";
    }

    /**
     * The inbox preview line. Hidden in the body by every mechanism clients
     * respect at once — zero dimensions, no opacity, pushed off-screen —
     * because no single one of them works everywhere. The run of entities
     * after it stops the client from padding the preview out with whatever
     * text follows, which is the whole point of writing one.
     */
    private static String preheader(String text) {
        return "<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;"
                + "mso-hide:all;font-size:1px;line-height:1px;color:transparent;\">"
                + escape(text)
                + "&#847;&zwnj;&nbsp;&#847;&zwnj;&nbsp;&#847;&zwnj;&nbsp;&#847;&zwnj;&nbsp;"
                + "&#847;&zwnj;&nbsp;&#847;&zwnj;&nbsp;&#847;&zwnj;&nbsp;&#847;&zwnj;&nbsp;"
                + "</div>";
    }

    /** The application's own logo, linked back to the site. */
    private String logo() {
        String origin = properties.frontendOrigin();
        return "<a href=\"" + origin + "\" style=\"display:inline-block;text-decoration:none;\">"
                + "<img src=\"" + origin + "/logo-coc-docs-platform.png\" "
                + "width=\"" + LOGO_WIDTH + "\" height=\"" + LOGO_HEIGHT + "\" "
                + "alt=\"Coc Docs Platform\" "
                // border:0 for Outlook, which otherwise draws a link border
                // around it; the explicit height keeps the ratio when a
                // client honours width but not the attribute.
                + "style=\"display:block;margin:0 auto 24px;border:0;outline:none;text-decoration:none;"
                + "width:" + LOGO_WIDTH + "px;height:" + LOGO_HEIGHT + "px;\"></a>";
    }

    // -----------------------------------------------------------------
    // Content pieces. Each returns a fragment for `page`'s contentHtml.
    // -----------------------------------------------------------------

    /** The one line that says what this email is. */
    public static String heading(String text) {
        return "<h1 style=\"margin:0 0 12px;font-size:23px;line-height:1.3;color:" + INK_HEADING + ";"
                + "font-weight:700;letter-spacing:-.01em;\">" + escape(text) + "</h1>";
    }

    /** The sentence under the heading. Left-aligned: centred body copy is
     *  harder to read past a single line, and every one of these runs to two. */
    public static String lead(String text) {
        return "<p style=\"margin:0 0 28px;font-size:15px;line-height:1.65;color:" + INK_BODY + ";"
                + "text-align:left;\">" + escape(text) + "</p>";
    }

    /** The primary action. Solid colour first so Outlook, which ignores the
     *  gradient, still gets a filled button rather than white-on-white. */
    public static String button(String href, String label) {
        return "<a href=\"" + href + "\" style=\"display:inline-block;padding:15px 40px;border-radius:14px;"
                + "background-color:" + BRAND_PURPLE + ";"
                + "background:linear-gradient(135deg," + BRAND_PURPLE + "," + BRAND_BLUE + ");"
                + "color:#ffffff;font-size:15px;font-weight:600;text-decoration:none;"
                + "box-shadow:0 10px 24px rgba(109,60,224,0.30);\">" + escape(label) + "</a>";
    }

    /**
     * A labelled read-only field — the invited address, or the temporary
     * password beside it.
     *
     * @param mono set for a credential, where the reader has to transcribe
     *     the value and 0/O and 1/l must be distinguishable
     * @param accent the left rule's colour, so two stacked fields read as
     *     two things rather than one block
     */
    public static String field(String label, String value, boolean mono, String accent) {
        return "<p style=\"margin:0 0 6px;font-size:11px;font-weight:700;color:#8b8ba7;text-align:left;"
                + "letter-spacing:.06em;text-transform:uppercase;\">" + escape(label) + "</p>"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
                + "style=\"margin:0;border-radius:12px;background-color:#f7f7fc;\"><tr>"
                + "<td style=\"padding:14px 18px;border-left:3px solid " + accent + ";border-radius:12px;"
                + "text-align:left;color:" + INK_HEADING + ";"
                + (mono
                        ? "font-family:" + FONT_MONO + ";font-size:19px;font-weight:700;letter-spacing:.05em;"
                        : "font-size:14px;font-weight:600;")
                + "word-break:break-all;\">" + escape(value) + "</td>"
                + "</tr></table>";
    }

    /** Accent colours for {@link #field}, so callers need no hex literals. */
    public static String accentBlue() {
        return BRAND_BLUE;
    }

    public static String accentPurple() {
        return BRAND_PURPLE;
    }

    /** A one-time code, shown large enough to read off a phone. */
    public static String code(String digits) {
        return "<div style=\"display:inline-block;padding:18px 30px;border-radius:14px;"
                + "background-color:#f4f2fd;border:1px solid #e7e1fb;"
                + "font-family:" + FONT_MONO + ";font-size:32px;font-weight:800;letter-spacing:8px;"
                // The tracking above is applied to the right of every glyph
                // including the last, which visually shifts the code off
                // centre. This takes that one step back.
                + "text-indent:8px;color:" + INK_HEADING + ";\">" + escape(digits) + "</div>";
    }

    /** A checked line of guidance between the fields and the button. */
    public static String reassurance(String text) {
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
                + "style=\"margin:20px 0 28px;\"><tr>"
                + "<td width=\"20\" valign=\"top\" style=\"padding-right:8px;\">"
                + "<span style=\"display:inline-block;width:16px;height:16px;line-height:16px;border-radius:50%;"
                + "background-color:#1f9d6b;color:#ffffff;font-size:10px;font-weight:700;text-align:center;\">"
                + "&#10003;</span></td>"
                + "<td style=\"font-size:14px;line-height:1.6;color:" + INK_BODY + ";text-align:left;\">"
                + escape(text) + "</td></tr></table>";
    }

    /** Expiry, single use, what to do if you didn't ask for this. */
    public static String footnote(String html) {
        return "<hr style=\"margin:32px 0 20px;border:none;border-top:1px solid " + HAIRLINE + ";\">"
                + "<p style=\"margin:0;font-size:12px;line-height:1.6;color:" + INK_BODY + ";text-align:left;\">"
                + html + "</p>";
    }

    public static String spacer(int px) {
        return "<div style=\"height:" + px + "px;line-height:" + px + "px;font-size:0;\">&nbsp;</div>";
    }

    /**
     * Escapes text interpolated into the HTML body.
     *
     * <p>Every value these templates carry today is machine-generated (a
     * token, a code, a password) or an email address the account table
     * already holds, so none of them can currently contain markup. This is
     * here so that stays true of the next caller as well: an email body is
     * HTML assembled by string concatenation, and that is exactly the shape
     * that turns a stored address into an injection the first time somebody
     * passes something less controlled through it.
     *
     * <p>Not applied to {@link #footnote}, whose argument is fixed template
     * copy containing deliberate {@code <strong>} markup, and not to URLs,
     * which are built from a configured origin plus a hex token.
     */
    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
