package com.coc.docs.notify;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one HRMS attendance mail into {@link HrmsMailContent}.
 *
 * <p><b>Regex, not an HTML parser, and that is a considered choice.</b> The
 * input is not the web: it is one machine's template, from one sender this
 * platform already pins by address, producing a single table with a fixed five
 * cells per row. Adding jsoup to the backend to read it would be a dependency —
 * and a supply-chain surface — bought for one screen. The cost of the choice is
 * that a template change breaks parsing, which is why nothing here throws:
 * every failure mode yields a report with no rows, and the UI says it could not
 * read the mail rather than showing a confident empty table.
 *
 * <p><b>The shape it expects</b>, confirmed against a real report:
 *
 * <pre>
 *   &lt;h3&gt;...&lt;span&gt;BeplePay Department&lt;/span&gt;...&lt;/h3&gt;
 *   &lt;table&gt;
 *     &lt;tr&gt;&lt;th&gt;Name&lt;/th&gt;&lt;th colspan=2&gt;Yesterday Check-Out&lt;/th&gt;
 *         &lt;th colspan=2&gt;Today Check-In&lt;/th&gt;&lt;/tr&gt;
 *     &lt;tr&gt;&lt;td&gt;NAME&lt;/td&gt;
 *         &lt;td&gt;&lt;span&gt;17:00&lt;/span&gt; -&gt; &lt;span&gt;17:44&lt;/span&gt;&lt;/td&gt;
 *         &lt;td&gt;&lt;div&gt;&lt;span&gt;Normal&lt;/span&gt;&lt;/div&gt;&lt;/td&gt;
 *         &lt;td&gt;&lt;span&gt;08:00&lt;/span&gt; -&gt; &lt;span&gt;08:01&lt;/span&gt;&lt;/td&gt;
 *         &lt;td&gt;&lt;div&gt;&lt;span&gt;Late Check-In&lt;/span&gt;&lt;/div&gt;&lt;/td&gt;&lt;/tr&gt;
 *   &lt;/table&gt;
 * </pre>
 *
 * <p>Rows are matched by <em>cell count</em> rather than by position, so the
 * header row (three {@code th}) is skipped without needing to be recognised,
 * and a footer or spacer row cannot be mistaken for a person.
 *
 * <p>// req: FR-OPS-001, NFR-SEC-PII
 */
final class HrmsReportParser {

    private HrmsReportParser() {}

    private static final Pattern ROW = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>");

    /**
     * Cells are split on their <em>opening</em> tags, and this is the whole
     * reason this parser is not four lines shorter.
     *
     * <p>The real mail does not close its last {@code <td>} in a row — a report
     * with 18 rows carries 90 {@code <td>} and 72 {@code </td>}. A
     * {@code <td>(.*?)</td>} pattern therefore finds four cells in a
     * five-cell row, every row fails the count check below, and the whole
     * report parses to nothing. It did exactly that in production while a
     * fixture written with well-formed HTML passed.
     *
     * <p>Splitting on openings is agnostic to whether the closing tag is there:
     * a cell runs from its own tag to the next one, or to the end of the row.
     * {@code text()} strips any stray {@code </td>} along with everything else.
     */
    private static final Pattern CELL_OPEN = Pattern.compile("(?is)<td[^>]*>");
    private static final Pattern HEADING = Pattern.compile("(?is)<h3[^>]*>(.*?)</h3>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");

    /**
     * The two halves of a check cell: the scheduled time, then the actual one
     * after the arrow.
     *
     * <p>The arrow is matched as {@code -&gt;} or the literal {@code ->},
     * because which one arrives depends on whether the sender escaped it, and
     * a report that parses in staging and not in production over one entity is
     * not a debugging session anyone should have.
     */
    private static final Pattern CHECK = Pattern.compile("(?is)^(.*?)(?:-&gt;|->)(.*)$");

    /** Exactly the five cells one person's row has. */
    private static final int CELLS_PER_ROW = 5;

    /** The label/value pair every other HRMS mail lays its fields out in. */
    private static final int CELLS_PER_DETAIL = 2;

    /** The "do not reply" line these templates close with. */
    private static final Pattern FOOTER = Pattern.compile("(?is)<h5[^>]*>(.*?)</h5>");

    /**
     * Parses {@code html}, or returns an empty report if it cannot.
     *
     * @param subject the mail's subject, carried through rather than re-read
     *     from the body — the feed already shows it and the two must agree
     * @param reportDate the parsed report day, or null
     */
    static HrmsMailContent parse(String subject, LocalDate reportDate, String html) {
        if (html == null || html.isBlank()) {
            return new HrmsMailContent(subject, reportDate, "", List.of(), List.of(), "");
        }

        List<HrmsMailContent.Row> rows = new ArrayList<>();
        List<HrmsMailContent.Detail> details = new ArrayList<>();

        Matcher row = ROW.matcher(html);
        while (row.find()) {
            List<String> cells = cellsOf(row.group(1));

            // Five cells is a person in the attendance table; two is a labelled
            // field in every other HRMS mail. The two shapes never appear in
            // the same message — a report has no two-cell rows and a leave
            // request has no five-cell ones — so one pass reads either.
            if (cells.size() == CELLS_PER_ROW) {
                String name = text(cells.get(0));
                if (!name.isBlank()) {
                    rows.add(new HrmsMailContent.Row(
                            name,
                            entry(cells.get(1), cells.get(2)),
                            entry(cells.get(3), cells.get(4))));
                }
            } else if (cells.size() == CELLS_PER_DETAIL) {
                String label = text(cells.get(0));
                String value = text(cells.get(1));
                if (!label.isBlank() || !value.isBlank()) {
                    details.add(new HrmsMailContent.Detail(label, value));
                }
            }
        }

        return new HrmsMailContent(subject, reportDate, department(html), rows, details, note(html));
    }

    /** One entry per {@code <td>}, each running to the next one or to the row's end. */
    private static List<String> cellsOf(String rowHtml) {
        List<String> cells = new ArrayList<>(CELLS_PER_ROW);
        Matcher open = CELL_OPEN.matcher(rowHtml);
        int contentStart = -1;
        while (open.find()) {
            if (contentStart >= 0) {
                cells.add(rowHtml.substring(contentStart, open.start()));
            }
            contentStart = open.end();
        }
        if (contentStart >= 0) {
            cells.add(rowHtml.substring(contentStart));
        }
        return cells;
    }

    /**
     * One check: {@code 17:00 -> 17:44} from the times cell, and HRMS's word
     * for it from the status cell beside it.
     *
     * <p>A cell with no arrow puts everything in {@code scheduled} and leaves
     * {@code actual} empty, rather than guessing which half is missing. That is
     * what the screen then shows, which is the honest rendering of a cell this
     * parser did not understand.
     */
    private static HrmsMailContent.Entry entry(String timesCell, String statusCell) {
        String times = text(timesCell);
        Matcher split = CHECK.matcher(times);
        if (split.matches()) {
            return new HrmsMailContent.Entry(
                    split.group(1).trim(), split.group(2).trim(), text(statusCell));
        }
        return new HrmsMailContent.Entry(times, "", text(statusCell));
    }

    /**
     * The mail's own heading, or blank — a report is still worth showing
     * without one.
     *
     * <p>Serves both shapes: it is the department above an attendance table and
     * the title ("Leave Request") above a labelled field list. Same tag, same
     * position, so it needs no second pattern.
     */
    private static String department(String html) {
        Matcher heading = HEADING.matcher(html);
        return heading.find() ? text(heading.group(1)) : "";
    }

    /**
     * The footer line, kept rather than dropped.
     *
     * <p>It says the mail is automated and must not be replied to. That is
     * exactly the thing a reader needs to know when they are looking at it
     * inside another product instead of in their mail client, where the Reply
     * button would be right there.
     */
    private static String note(String html) {
        Matcher footer = FOOTER.matcher(html);
        return footer.find() ? text(footer.group(1)) : "";
    }

    /**
     * The visible text of a fragment.
     *
     * <p>Entities are decoded for the handful the sender actually emits. This
     * is not a general HTML unescaper and does not need to be: the output is
     * JSON read by a React client that escapes on render, so an entity missed
     * here is a cosmetic {@code &amp;} in a name, never an injection.
     */
    private static String text(String fragment) {
        String stripped = TAG.matcher(fragment).replaceAll(" ");
        return stripped
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
