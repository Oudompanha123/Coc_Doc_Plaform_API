package com.coc.docs.announcement;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * An announcement body's words, without its Markdown, for a push banner
 * (ADR-103).
 *
 * <p>The body is Markdown (ADR-102), rendered for readers by the frontend's
 * parser. A banner cannot render anything, so it gets the text with the
 * markers taken off: "## **Portal** offline" becomes "Portal offline", a link
 * becomes its label, a coloured span its words. This mirrors
 * {@code markdownToPlainText} in {@code frontend/lib/markdown/parse.ts} for the
 * same subset; it does not need to match it character for character, only to
 * never show a reader a stray {@code **}.
 *
 * <p>Pure string handling. The result goes into a push payload as text, never
 * as markup.
 *
 * <p>// req: ADR-102, ADR-103
 */
public final class AnnouncementText {

    private static final Pattern FENCE = Pattern.compile("^\\s*```.*$");
    private static final Pattern RULE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern BLOCK_PREFIX =
            Pattern.compile("^\\s*(?:#{1,6}\\s+|>\\s?|[-*+]\\s+|\\d{1,9}[.)]\\s+)+");
    private static final Pattern COLOR = Pattern.compile("\\[([^\\]\\n]+)\\]\\{color=[a-z]+\\}");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]\\n]+)\\]\\(\\s*(?:[^()\\s]|\\([^()\\s]*\\))+\\s*\\)");
    private static final Pattern STRONG = Pattern.compile("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1");
    private static final Pattern EM_STAR = Pattern.compile("\\*(?=[^\\s*])(.+?)(?<=[^\\s*])\\*");
    private static final Pattern EM_UNDERSCORE = Pattern.compile("(?<![A-Za-z0-9])_(?=[^\\s_])(.+?)(?<=[^\\s_])_(?![A-Za-z0-9_])");
    private static final Pattern CODE = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern ESCAPE = Pattern.compile("\\\\([\\\\`*_\\[\\]()#>+\\-.!{}])");
    private static final Pattern TRAILING_HASHES = Pattern.compile("\\s+#+\\s*$");

    /**
     * How much of a body a push banner and its bell row carry (ADR-103). Long
     * enough for a few sentences, short enough that no platform truncates it
     * into something misleading on its own, and well inside FCM's 4 KB payload
     * with the title beside it.
     */
    public static final int BANNER_EXCERPT_LENGTH = 240;

    private AnnouncementText() {}

    /** The words of {@code markdown}, one line per non-empty source line. */
    public static String plainText(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        List<String> out = new ArrayList<>();
        boolean inFence = false;
        for (String raw : markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            if (FENCE.matcher(raw).matches()) {
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                // Code is shown as written: its markers are its content.
                if (!raw.isBlank()) out.add(raw.strip());
                continue;
            }
            if (raw.isBlank() || RULE.matcher(raw).matches()) {
                continue;
            }
            String line = BLOCK_PREFIX.matcher(raw).replaceFirst("");
            line = TRAILING_HASHES.matcher(line).replaceFirst("");
            line = inline(line).strip();
            if (!line.isEmpty()) out.add(line);
        }
        return String.join("\n", out);
    }

    /**
     * {@link #plainText} cut to at most {@code max} characters, at a word
     * boundary where there is one, with an ellipsis when anything was cut.
     */
    public static String excerpt(String markdown, int max) {
        // One line: a banner shows only the first line of its text on Windows,
        // so "Dear Yuth\nThe portal is…" showed as just "Dear Yuth".
        String text = plainText(markdown).replace('\n', ' ');
        if (text.length() <= max) {
            return text;
        }
        int cut = max - 1;
        int space = text.lastIndexOf(' ', cut);
        // Only back up to a space that keeps most of the text; one long word
        // is cut mid-word rather than reduced to nothing.
        if (space > max / 2) cut = space;
        return text.substring(0, cut).stripTrailing() + "…";
    }

    private static String inline(String line) {
        // Escapes first, to placeholders, so "\*" is never read as emphasis.
        List<String> escaped = new ArrayList<>();
        var m = ESCAPE.matcher(line);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            escaped.add(m.group(1));
            m.appendReplacement(sb, "\u0000" + (escaped.size() - 1) + "\u0000");
        }
        m.appendTail(sb);
        String s = sb.toString();

        s = replaceAll(CODE, s);
        s = replaceAll(COLOR, s);
        s = replaceAll(LINK, s);
        // Twice: "**bold _italic_**" needs the outer pass to expose the inner.
        for (int i = 0; i < 2; i++) {
            s = STRONG.matcher(s).replaceAll(r -> java.util.regex.Matcher.quoteReplacement(r.group(2)));
            s = replaceAll(EM_STAR, s);
            s = replaceAll(EM_UNDERSCORE, s);
        }

        for (int i = 0; i < escaped.size(); i++) {
            s = s.replace("\u0000" + i + "\u0000", escaped.get(i));
        }
        return s;
    }

    /** Replaces each match with its first group, taken literally. */
    private static String replaceAll(Pattern pattern, String s) {
        return pattern.matcher(s).replaceAll(r -> java.util.regex.Matcher.quoteReplacement(r.group(1)));
    }
}
