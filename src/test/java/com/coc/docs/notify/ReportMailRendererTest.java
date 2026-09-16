package com.coc.docs.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The renderer, with no Spring context and no mail server — it is a pure
 * function from a {@link ReportMail} to two strings, and the parts worth
 * testing are the escape and the plain-text alternative, both of which are
 * easy to break and invisible in a visual check of the HTML.
 *
 * <p>// req: NFR-SEC-SANITIZE, FR-OPS-001
 */
class ReportMailRendererTest {

    private final ReportMailRenderer renderer =
            new ReportMailRenderer(new NotificationProperties(null, null, null, false));

    private static ReportMail report(ReportMail.Section... sections) {
        return new ReportMail("Content activity", "15-Sep-2026", List.of(sections), "footer");
    }

    private static ReportMail.Section section(String heading, ReportMail.Row... rows) {
        return new ReportMail.Section(heading, List.of("Page", "Status"), List.of(rows));
    }

    @Test
    @DisplayName("renders each section heading and every row")
    void rendersSections() {
        String html = renderer.html(report(
                section(
                        "jex2.0",
                        ReportMail.Row.of(ReportMail.Cell.of("Getting Started"), ReportMail.Cell.of("Published")),
                        ReportMail.Row.of(ReportMail.Cell.of("API Keys"), ReportMail.Cell.alert("Draft pending"))),
                section("guides", ReportMail.Row.of(ReportMail.Cell.of("Install"), ReportMail.Cell.of("Published")))));

        assertThat(html)
                .contains("jex2.0")
                .contains("guides")
                .contains("Getting Started")
                .contains("API Keys")
                .contains("Install");
    }

    @Test
    @DisplayName("an ALERT cell is highlighted and a NORMAL one is not")
    void highlightsAlerts() {
        String html = renderer.html(report(section(
                "jex2.0",
                ReportMail.Row.of(ReportMail.Cell.of("Quiet page"), ReportMail.Cell.of("Published")),
                ReportMail.Row.of(ReportMail.Cell.of("Loud page"), ReportMail.Cell.alert("Draft pending")))));

        // The highlight colour appears for the section heading and for the one
        // alert cell — not for the ordinary row.
        assertThat(html.split("#ffe94d", -1).length - 1).isEqualTo(2);
    }

    @Test
    @DisplayName("cell text is escaped, so a page title cannot inject markup")
    void escapesCellText() {
        String html = renderer.html(report(section(
                "jex2.0",
                ReportMail.Row.of(
                        ReportMail.Cell.of("<script>alert('x')</script>"), ReportMail.Cell.of("Published")))));

        assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;");
    }

    @Test
    @DisplayName("a section heading is escaped too")
    void escapesHeadings() {
        String html = renderer.html(
                report(section("<b>jex", ReportMail.Row.of(ReportMail.Cell.of("p"), ReportMail.Cell.of("s")))));

        assertThat(html).doesNotContain("<b>jex").contains("&lt;b&gt;jex");
    }

    @Test
    @DisplayName("the plain-text alternative carries the same rows, column-aligned")
    void rendersPlainText() {
        String text = renderer.text(report(section(
                "jex2.0",
                ReportMail.Row.of(ReportMail.Cell.of("Getting Started"), ReportMail.Cell.of("Published")),
                ReportMail.Row.of(ReportMail.Cell.of("API"), ReportMail.Cell.alert("Draft pending")))));

        assertThat(text).contains("* jex2.0").contains("Getting Started").contains("Draft pending");
        // The short title is padded out to the widest value in its column, so
        // the second column lines up in a fixed-width client.
        assertThat(text).contains("API              Draft pending");
    }

    @Test
    @DisplayName("an empty report says so rather than rendering an empty table")
    void handlesEmptyReport() {
        ReportMail empty = new ReportMail("Content activity", "15-Sep-2026", List.of(), "footer");

        assertThat(empty.isEmpty()).isTrue();
        assertThat(renderer.html(empty)).contains("Nothing to report");
        assertThat(renderer.text(empty)).contains("Nothing to report");
    }

    @Test
    @DisplayName("a section with no rows is skipped, not rendered as a bare heading")
    void skipsEmptySections() {
        ReportMail mixed = report(
                section("empty-section"),
                section("jex2.0", ReportMail.Row.of(ReportMail.Cell.of("Page"), ReportMail.Cell.of("Published"))));

        assertThat(renderer.html(mixed)).doesNotContain("empty-section").contains("jex2.0");
    }

    @Test
    @DisplayName("a row whose cell count disagrees with the columns is rejected at construction")
    void rejectsRaggedRows() {
        assertThatThrownBy(() -> new ReportMail.Section(
                        "jex2.0", List.of("Page", "Status"), List.of(ReportMail.Row.of(ReportMail.Cell.of("only")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2 columns");
    }

    @Test
    @DisplayName("rowCount counts across sections")
    void countsRows() {
        ReportMail mail = report(
                section(
                        "a",
                        ReportMail.Row.of(ReportMail.Cell.of("1"), ReportMail.Cell.of("x")),
                        ReportMail.Row.of(ReportMail.Cell.of("2"), ReportMail.Cell.of("y"))),
                section("b", ReportMail.Row.of(ReportMail.Cell.of("3"), ReportMail.Cell.of("z"))));

        assertThat(mail.rowCount()).isEqualTo(3);
        assertThat(mail.isEmpty()).isFalse();
    }
}
