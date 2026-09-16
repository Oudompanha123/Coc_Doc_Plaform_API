package com.coc.docs.notify;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Renders a {@link ReportMail} as the HTML body and the plain-text
 * alternative.
 *
 * <p><b>Inline styles, on purpose.</b> The same reasoning
 * {@code PasswordResetMailer} records: a {@code <style>} block is stripped by
 * enough mail clients — Outlook desktop chief among them — that it cannot be
 * relied on, and there is no asset host to point an {@code <img>} at, so the
 * brand mark is a coloured badge rather than an image.
 *
 * <p><b>Everything is escaped.</b> Cell text comes from page titles and
 * display names, both of which are user input. A report email is read in a
 * client that will happily render markup, so the escape is not optional and is
 * not left to call sites to remember — it happens here, once, for every string
 * that reaches the document.
 *
 * <p>// req: NFR-SEC-SANITIZE, FR-OPS-001
 */
@Component
public class ReportMailRenderer {

    private static final String FONT = "-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif";
    private static final String MONO = "SFMono-Regular,Consolas,Liberation Mono,monospace";

    private static final String INK = "#16141f";
    private static final String MUTED_INK = "#6b6b80";
    private static final String RULE = "#ececf2";
    private static final String HIGHLIGHT = "#ffe94d";
    private static final String BASELINE_BG = "#8e8e9c";

    private final String brandName;

    public ReportMailRenderer(NotificationProperties properties) {
        this.brandName = properties.brandName();
    }

    public String html(ReportMail report) {
        StringBuilder html = new StringBuilder(2048);
        html.append("<!doctype html>")
                // The MIME part already declares UTF-8, but page titles here
                // are Khmer and Korean as often as English (CONST-OPS-01) and
                // enough clients strip MIME headers when forwarding or
                // printing that the document has to say so itself.
                .append("<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>")
                .append("<body style=\"margin:0;padding:32px 16px;background-color:#eef0f8;")
                .append("font-family:").append(FONT).append(";\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\">")
                .append("<tr><td align=\"center\">")
                .append("<table role=\"presentation\" width=\"720\" cellpadding=\"0\" cellspacing=\"0\" ")
                .append("style=\"max-width:720px;width:100%;background-color:#ffffff;border-radius:16px;")
                .append("box-shadow:0 4px 24px rgba(40,34,109,0.08);\">")
                .append("<tr><td style=\"padding:36px 36px 32px;\">");

        // Header: badge, title, period.
        html.append("<div style=\"width:44px;height:44px;margin:0 0 20px;border-radius:12px;")
                .append("background:linear-gradient(135deg,#6d3ce0,#4c6ff0);color:#ffffff;font-size:16px;")
                .append("font-weight:700;line-height:44px;text-align:center;\">CD</div>")
                .append("<h1 style=\"margin:0 0 4px;font-size:20px;line-height:1.3;color:").append(INK)
                .append(";font-weight:800;\">").append(escape(report.title())).append("</h1>")
                .append("<p style=\"margin:0 0 28px;font-size:13px;color:").append(MUTED_INK)
                .append(";\">").append(escape(report.subtitle())).append("</p>");

        if (report.isEmpty()) {
            html.append("<p style=\"margin:0;font-size:14px;color:").append(MUTED_INK)
                    .append(";\">Nothing to report for this period.</p>");
        } else {
            for (ReportMail.Section section : report.sections()) {
                if (section.rows().isEmpty()) {
                    // A group with no rows is noise in a digest, not information.
                    continue;
                }
                appendSection(html, section);
            }
        }

        html.append("<hr style=\"margin:32px 0 16px;border:none;border-top:1px solid ").append(RULE).append(";\">")
                .append("<p style=\"margin:0;font-size:12px;line-height:1.6;color:").append(MUTED_INK)
                .append(";\">").append(escape(report.footerNote())).append("</p>")
                .append("</td></tr></table>")
                .append("<p style=\"margin:20px 0 0;font-size:12px;color:").append(MUTED_INK)
                .append(";text-align:center;\">").append(escape(brandName))
                .append(" — this is an automated message, please do not reply.</p>")
                .append("</td></tr></table></body></html>");

        return html.toString();
    }

    private void appendSection(StringBuilder html, ReportMail.Section section) {
        html.append("<div style=\"margin:0 0 10px;font-size:15px;font-weight:700;color:").append(INK)
                .append(";\">&bull; <span style=\"background-color:").append(HIGHLIGHT)
                .append(";padding:2px 6px;border-radius:3px;\">")
                .append(escape(section.heading()))
                .append("</span></div>")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
                .append("style=\"border-collapse:collapse;margin:0 0 28px;\">");

        html.append("<tr>");
        for (String column : section.columns()) {
            html.append("<th align=\"left\" style=\"padding:8px 12px 8px 0;font-size:12px;font-weight:700;")
                    .append("color:").append(INK).append(";border-bottom:1px solid ").append(RULE)
                    .append(";text-align:left;\">").append(escape(column)).append("</th>");
        }
        html.append("</tr>");

        for (ReportMail.Row row : section.rows()) {
            html.append("<tr>");
            for (ReportMail.Cell cell : row.cells()) {
                html.append("<td style=\"padding:9px 12px 9px 0;font-size:13px;vertical-align:top;")
                        .append("border-bottom:1px solid #f5f5f9;\">")
                        .append(cellHtml(cell))
                        .append("</td>");
            }
            html.append("</tr>");
        }

        html.append("</table>");
    }

    private static String cellHtml(ReportMail.Cell cell) {
        String text = escape(cell.text());
        return switch (cell.emphasis()) {
            case NORMAL -> "<span style=\"color:" + INK + ";\">" + text + "</span>";
            case MUTED -> "<span style=\"color:" + MUTED_INK + ";\">" + text + "</span>";
            case BASELINE -> "<span style=\"background-color:" + BASELINE_BG + ";color:#ffffff;"
                    + "padding:2px 6px;border-radius:3px;font-family:" + MONO + ";font-size:12px;\">"
                    + text + "</span>";
            case ALERT -> "<span style=\"background-color:" + HIGHLIGHT + ";color:" + INK + ";"
                    + "padding:2px 6px;border-radius:3px;font-weight:700;\">" + text + "</span>";
        };
    }

    /**
     * The plain-text alternative.
     *
     * <p>Not a courtesy: a multipart message whose text part is missing shows
     * as an empty message in a client set to prefer text, and columns that are
     * not padded are unreadable in one. So the widths are computed from the
     * content rather than guessed.
     */
    public String text(ReportMail report) {
        StringBuilder out = new StringBuilder(1024);
        out.append(report.title()).append('\n').append(report.subtitle()).append("\n\n");

        if (report.isEmpty()) {
            out.append("Nothing to report for this period.\n");
        } else {
            for (ReportMail.Section section : report.sections()) {
                if (section.rows().isEmpty()) {
                    continue;
                }
                out.append("* ").append(section.heading()).append('\n');

                int[] widths = columnWidths(section);
                appendTextRow(out, section.columns(), widths);
                for (ReportMail.Row row : section.rows()) {
                    appendTextRow(out, row.cells().stream().map(ReportMail.Cell::text).toList(), widths);
                }
                out.append('\n');
            }
        }

        out.append("--\n").append(report.footerNote()).append('\n')
                .append(brandName).append(" — automated message, please do not reply.\n");
        return out.toString();
    }

    private static int[] columnWidths(ReportMail.Section section) {
        int[] widths = new int[section.columns().size()];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = section.columns().get(i).length();
        }
        for (ReportMail.Row row : section.rows()) {
            for (int i = 0; i < widths.length; i++) {
                widths[i] = Math.max(widths[i], row.cells().get(i).text().length());
            }
        }
        return widths;
    }

    private static void appendTextRow(StringBuilder out, List<String> values, int[] widths) {
        for (int i = 0; i < values.size(); i++) {
            boolean last = i == values.size() - 1;
            out.append(pad(values.get(i), last ? 0 : widths[i]));
            if (!last) {
                out.append("  ");
            }
        }
        out.append('\n');
    }

    private static String pad(String value, int width) {
        if (value.length() >= width) {
            return value;
        }
        return value + " ".repeat(width - value.length());
    }

    /**
     * HTML-escapes one string.
     *
     * <p>Quotes included: cell text never reaches an attribute today, but the
     * escape that only holds while that stays true is the one that breaks
     * quietly when someone renders a title into a {@code title=""} later.
     */
    static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
