package com.coc.docs.notify;

import java.util.List;

/**
 * A tabular report email, expressed as data rather than as markup.
 *
 * <p>The shape is deliberately narrow: a heading, and under it one or more
 * named groups, each a small table whose cells carry an {@link Emphasis}
 * instead of styling. Everything about how that becomes an email — inline
 * CSS, the plain-text alternative, escaping — belongs to
 * {@link ReportMailRenderer} and to nothing else, so a second report (a
 * weekly roll-up, a translation-coverage report) is a new set of rows and not
 * a second copy of a hand-written HTML string.
 *
 * <p>That split is the lesson of the three mailers already in {@code auth}:
 * {@code PasswordResetMailer}, {@code AccountInviteMailer} and
 * {@code RegistrationOtpMailer} each concatenate their own near-identical
 * document, and a change to the brand header has to be made three times.
 *
 * @param title the headline, e.g. {@code "Content activity"}
 * @param subtitle the line under it, usually the period the report covers
 * @param sections the groups, rendered in order; an empty list renders as
 *     "nothing to report" rather than an empty table
 * @param footerNote small print under the rule at the bottom
 *
 * <p>// req: FR-OPS-001
 */
public record ReportMail(String title, String subtitle, List<Section> sections, String footerNote) {

    public ReportMail {
        sections = sections == null ? List.of() : List.copyOf(sections);
    }

    /** Whether any section holds at least one row. */
    public boolean isEmpty() {
        return sections.stream().allMatch(s -> s.rows().isEmpty());
    }

    /** The total row count across every section. */
    public int rowCount() {
        return sections.stream().mapToInt(s -> s.rows().size()).sum();
    }

    /**
     * One named group — a department in the sample this was modelled on, a
     * documentation section here.
     *
     * @param heading the group name, rendered as the highlighted bullet
     * @param columns the header row; every row in this section must have the
     *     same number of cells
     * @param rows the body rows, rendered in the order given
     */
    public record Section(String heading, List<String> columns, List<Row> rows) {

        public Section {
            columns = columns == null ? List.of() : List.copyOf(columns);
            rows = rows == null ? List.of() : List.copyOf(rows);

            for (Row row : rows) {
                if (row.cells().size() != columns.size()) {
                    // Caught here rather than producing a table that renders
                    // with a ragged final column — which looks like a styling
                    // bug and gets reported as one, days later, by a reader.
                    throw new IllegalArgumentException(
                            "section '" + heading + "' declares " + columns.size()
                                    + " columns but a row has " + row.cells().size() + " cells");
                }
            }
        }
    }

    /** One body row. */
    public record Row(List<Cell> cells) {

        public Row {
            cells = cells == null ? List.of() : List.copyOf(cells);
        }

        /** Convenience for the common case of building a row inline. */
        public static Row of(Cell... cells) {
            return new Row(List.of(cells));
        }
    }

    /**
     * One cell: text plus how much attention it should draw.
     *
     * <p>Text, never markup. The renderer escapes it, so a page title
     * containing {@code <} or a display name someone set to
     * {@code <script>} cannot reach a mail client as anything but characters.
     */
    public record Cell(String text, Emphasis emphasis) {

        public Cell {
            text = text == null ? "" : text;
            emphasis = emphasis == null ? Emphasis.NORMAL : emphasis;
        }

        public static Cell of(String text) {
            return new Cell(text, Emphasis.NORMAL);
        }

        public static Cell muted(String text) {
            return new Cell(text, Emphasis.MUTED);
        }

        public static Cell baseline(String text) {
            return new Cell(text, Emphasis.BASELINE);
        }

        public static Cell alert(String text) {
            return new Cell(text, Emphasis.ALERT);
        }
    }

    /**
     * How a cell reads, not how it is painted.
     *
     * <p>Naming these by meaning rather than by colour is what lets the palette
     * change in one place later without every call site having to agree about
     * what "yellow" was supposed to signify.
     */
    public enum Emphasis {
        /** Ordinary content. */
        NORMAL,
        /** Secondary detail — present, but not what the reader is scanning for. */
        MUTED,
        /** A reference value the row is measured against, e.g. an expected time. */
        BASELINE,
        /** Needs a human to look at it. Highlighted. */
        ALERT
    }
}
