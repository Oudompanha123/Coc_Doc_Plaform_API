package com.coc.docs.legal;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Authoritative check for the Markdown stored in the plain-language box and
 * every section body — mirrors the frontend's {@code markdown.ts}
 * {@code validateMarkdown} exactly, so the two never disagree (ADR-012 §3:
 * the frontend check is advisory, this is what a write path actually
 * enforces).
 *
 * <p>Markdown itself is never parsed into HTML here or on render — both
 * sides render the whitelisted dialect directly as React elements
 * ({@code MarkdownView}) / would render as plain data if ever needed
 * server-side, never {@code dangerouslySetInnerHTML} — so this validator's
 * job is narrow: reject raw HTML tags and non-whitelisted link schemes, and
 * enforce the length limit. Unsupported Markdown syntax (images, tables,
 * code blocks) isn't rejected — it simply isn't special syntax to the
 * renderer, so it comes back out as inert literal text (ADR-010 §3).
 */
@Component
public class MarkdownValidator {

    private static final Pattern HTML_TAG = Pattern.compile("</?[a-zA-Z][^>]*>");

    // One level of balanced parens tolerated inside the URL itself, so a
    // link whose target contains "(" doesn't truncate at that inner ")".
    private static final String HREF_PATTERN = "[^\\s()]*(?:\\([^()]*\\)[^\\s()]*)*";
    private static final Pattern LINK_HREF = Pattern.compile("\\[[^\\]]*]\\((" + HREF_PATTERN + ")\\)");

    /** Null if valid, else a message describing the first problem found. */
    public String validate(String source, int maxLength, String label) {
        if (source == null || source.isBlank()) {
            return label + " is required";
        }
        if (source.length() > maxLength) {
            return label + " exceeds " + maxLength + " characters";
        }
        if (HTML_TAG.matcher(source).find()) {
            return label + " contains HTML, which isn't allowed";
        }
        Matcher links = LINK_HREF.matcher(source);
        while (links.find()) {
            String href = links.group(1);
            if (!isAllowedLinkHref(href)) {
                return label + " has a link that isn't an https://, mailto:, or site-relative (/...) address";
            }
        }
        return null;
    }

    /** Same-origin relative paths ("/...") are allowed too, but not a
     *  scheme-relative "//host" URL, which silently points at a different
     *  origin. */
    public static boolean isAllowedLinkHref(String href) {
        if (href == null) {
            return false;
        }
        if (href.startsWith("https://") || href.startsWith("mailto:")) {
            return true;
        }
        return href.startsWith("/") && !href.startsWith("//");
    }
}
