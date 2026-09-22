package com.coc.docs.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The parser behind the attendance popup (ADR-042).
 *
 * <p><b>The fixture is invented.</b> Its markup is copied from a real report —
 * the nesting, the inline styles, the {@code -&gt;} between the two times, the
 * five cells per row — because that is what the parser has to survive. The
 * names and times are not: putting a real report in the repository would store
 * exactly the data {@code V21__hrms_notification.sql} keeps out of the
 * database, in a place that is backed up and shared more widely than the
 * database is.
 *
 * <p>// req: FR-OPS-001, NFR-SEC-PII
 */
class HrmsReportParserTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 8);

    /**
     * A row in the real report's shape: name, times, status, times, status.
     *
     * <p><b>The last {@code <td>} is deliberately left unclosed.</b> That is
     * what HRMS actually sends — 90 {@code <td>} against 72 {@code </td>} in an
     * 18-row report — and a fixture that tidied it up is why the first version
     * of this parser passed every test here and returned nothing at all in
     * production. The malformed markup is the specification.
     */
    private static String row(String name, String outSched, String outActual, String outStatus,
            String inSched, String inActual, String inStatus) {
        return """
                <tr style='border-bottom: 1px solid #ddd;'>
                  <td style='padding:8px 8px;'>%s</td>
                  <td style='padding:8px 8px;'><span style='background-color:#9e9e9e!important;'>%s</span> -> <span>%s</span></td>
                  <td style='padding:8px 8px;'><div><span style='color:#000!important;'>%s</span></div></td>
                  <td style='padding:8px 8px;'><span style='background-color:#9e9e9e!important;'>%s</span> -> <span>%s</span></td>
                  <td style='padding:8px 8px;'><div><span style='color:#4caf50!important;'>%s</span></div>
                </tr>
                """
                .formatted(name, outSched, outActual, outStatus, inSched, inActual, inStatus);
    }

    private static String report(String... rows) {
        return """
                <html><body>
                  <div style='text-align: center;'><img src="logo.png" alt="logo"></div>
                  <h3 style='display:list-item;'><span style='background-color:yellow;'><span>Example</span> Department</span></h3>
                  <table class="w3-">
                    <tr style='border-bottom: 1px solid #ddd;'>
                      <th>Name</th><th colspan="2">Yesterday Check-Out</th><th colspan="2">Today Check-In</th>
                    </tr>
                    %s
                  </table>
                </body></html>
                """
                .formatted(String.join("\n", rows));
    }

    @Nested
    @DisplayName("a well-formed report")
    class WellFormed {

        private final HrmsMailContent parsed = HrmsReportParser.parse(
                "Attendance on 08-Sep-2026",
                DAY,
                report(
                        row("Alpha One", "17:00", "17:44", "Normal", "08:00", "08:01", "Late Check-In"),
                        row("Beta Two", "17:00", "00:00", "Missed Check-Out", "08:00", "00:00", "Missed Check-In")));

        @Test
        @DisplayName("reads every person, in the order the mail listed them")
        void readsRowsInOrder() {
            // Order is not incidental: the reader recognises this table from
            // the email, and re-sorting it would make the two disagree.
            assertThat(parsed.rows()).extracting(HrmsMailContent.Row::name)
                    .containsExactly("Alpha One", "Beta Two");
        }

        @Test
        @DisplayName("splits each check into scheduled, actual and status")
        void splitsChecks() {
            HrmsMailContent.Row first = parsed.rows().get(0);

            assertThat(first.yesterdayCheckOut())
                    .isEqualTo(new HrmsMailContent.Entry("17:00", "17:44", "Normal"));
            assertThat(first.todayCheckIn())
                    .isEqualTo(new HrmsMailContent.Entry("08:00", "08:01", "Late Check-In"));
        }

        @Test
        @DisplayName("passes HRMS's own status wording through, unmapped")
        void keepsSenderWording() {
            // Not an enum: a status this build has never heard of still has to
            // reach the screen rather than become "UNKNOWN" or vanish.
            assertThat(parsed.rows().get(1).yesterdayCheckOut().status()).isEqualTo("Missed Check-Out");
            assertThat(parsed.rows().get(1).todayCheckIn().status()).isEqualTo("Missed Check-In");
        }

        @Test
        @DisplayName("keeps 00:00 rather than treating it as no time at all")
        void keepsZeroTimes() {
            // HRMS writes 00:00 for "this never happened". Parsing times into
            // LocalTime would make that either midnight or a dropped row.
            assertThat(parsed.rows().get(1).yesterdayCheckOut().actual()).isEqualTo("00:00");
        }

        @Test
        @DisplayName("takes the department from the heading, tags and all")
        void readsDepartment() {
            assertThat(parsed.department()).isEqualTo("Example Department");
        }

        @Test
        @DisplayName("skips the header row without having to recognise it")
        void skipsHeaderRow() {
            // Matched on cell count, so "Name" never becomes a person.
            assertThat(parsed.rows()).hasSize(2);
        }

        @Test
        @DisplayName("carries the subject and date through rather than re-reading them")
        void carriesSubjectAndDate() {
            assertThat(parsed.subject()).isEqualTo("Attendance on 08-Sep-2026");
            assertThat(parsed.reportDate()).isEqualTo(DAY);
        }
    }

    /**
     * HRMS sends more leave/overtime/account mail than it sends reports — 25
     * distinct subjects in one mailbox, of which "Attendance on …" is one.
     * Every one of them rendered as "could not be read" until the parser
     * learnt this shape, which is what the PM saw when they opened a rejected
     * leave request.
     */
    @Nested
    @DisplayName("the other HRMS mails")
    class Details {

        /** The real leave/overtime shape: a heading, label/value rows, a footer. */
        private static final String LEAVE = """
                <html><body>
                  <h3><span style='background-color:yellow;'>Leave Request</span></h3>
                  <table border="0">
                    <tr><td>From &nbsp;</td><td>08-Sep-2026</td></tr>
                    <tr><td>To &nbsp;</td><td>09-Sep-2026</td></tr>
                    <tr><td>Type &nbsp;</td><td>Annual Leave</td></tr>
                    <tr><td>Reason &nbsp;</td><td>Family</td></tr>
                  </table>
                  <hr>
                  <h5>*** This is an automatically generated message, please do not reply to this message ***</h5>
                </body></html>
                """;

        private final HrmsMailContent parsed =
                HrmsReportParser.parse("[KOSIGN] Your leave request has been rejected", null, LEAVE);

        @Test
        @DisplayName("reads the labelled fields, in order")
        void readsDetails() {
            assertThat(parsed.details())
                    .containsExactly(
                            new HrmsMailContent.Detail("From", "08-Sep-2026"),
                            new HrmsMailContent.Detail("To", "09-Sep-2026"),
                            new HrmsMailContent.Detail("Type", "Annual Leave"),
                            new HrmsMailContent.Detail("Reason", "Family"));
        }

        @Test
        @DisplayName("strips the &nbsp; the sender pads its labels with")
        void trimsLabels() {
            assertThat(parsed.details().get(0).label()).isEqualTo("From");
        }

        @Test
        @DisplayName("takes the heading from the same h3 an attendance report uses")
        void readsHeading() {
            assertThat(parsed.department()).isEqualTo("Leave Request");
        }

        @Test
        @DisplayName("keeps the do-not-reply footer")
        void keepsNote() {
            // Obvious in a mail client, not obvious in a dialog with no Reply
            // button to not press.
            assertThat(parsed.note()).contains("automatically generated");
        }

        @Test
        @DisplayName("is not reported as unreadable")
        void notEmpty() {
            // The bug this nest exists for: isEmpty() checked rows alone, so
            // every one of these mails claimed the parser had failed.
            assertThat(parsed.isEmpty()).isFalse();
            assertThat(parsed.rows()).isEmpty();
        }
    }

    @Nested
    @DisplayName("mail this parser cannot read")
    class Degraded {

        @Test
        @DisplayName("an unrecognisable body yields no rows rather than an exception")
        void unknownShape() {
            // The whole failure contract: HRMS changes its template and the
            // screen says "could not read it", rather than the request 500ing
            // or a confident empty table implying nobody was late.
            HrmsMailContent parsed =
                    HrmsReportParser.parse("Attendance on 08-Sep-2026", DAY, "<html><body><p>Redesigned!</p></body></html>");

            assertThat(parsed.isEmpty()).isTrue();
            assertThat(parsed.subject()).isEqualTo("Attendance on 08-Sep-2026");
        }

        @Test
        @DisplayName("a null or blank body is empty, not a crash")
        void noBody() {
            assertThat(HrmsReportParser.parse("s", DAY, null).isEmpty()).isTrue();
            assertThat(HrmsReportParser.parse("s", DAY, "   ").isEmpty()).isTrue();
        }

        @Test
        @DisplayName("an escaped arrow parses the same as a literal one")
        void escapedArrow() {
            // Whether the sender escapes it is not something to discover in
            // production, one entity at a time.
            String escaped = report(row("Alpha One", "17:00", "17:44", "Normal", "08:00", "08:01", "Normal"))
                    .replace("-&gt;", "->")
                    .replace("->", "-&gt;");

            HrmsMailContent parsed = HrmsReportParser.parse("s", DAY, escaped);

            assertThat(parsed.rows()).hasSize(1);
            assertThat(parsed.rows().get(0).yesterdayCheckOut().actual()).isEqualTo("17:44");
        }

        @Test
        @DisplayName("well-formed cells parse too, so tidied markup is not a regression")
        void closedCellsAlsoParse() {
            // The fixture is malformed because the sender is. If HRMS ever
            // starts closing its tags, that must not break the parser the
            // other way — this is the case the old implementation handled and
            // the new one must keep handling.
            String tidy = report(row("Alpha One", "17:00", "17:44", "Normal", "08:00", "08:01", "Normal"))
                    .replace("</div>\n                </tr>", "</div></td>\n                </tr>");

            HrmsMailContent parsed = HrmsReportParser.parse("s", DAY, tidy);

            assertThat(parsed.rows()).hasSize(1);
            assertThat(parsed.rows().get(0).todayCheckIn().status()).isEqualTo("Normal");
        }

        @Test
        @DisplayName("a report with no heading still shows its rows")
        void noDepartment() {
            String noHeading = report(row("Alpha One", "17:00", "17:44", "Normal", "08:00", "08:01", "Normal"))
                    .replaceAll("(?is)<h3.*?</h3>", "");

            HrmsMailContent parsed = HrmsReportParser.parse("s", DAY, noHeading);

            assertThat(parsed.department()).isEmpty();
            assertThat(parsed.rows()).hasSize(1);
        }
    }
}
