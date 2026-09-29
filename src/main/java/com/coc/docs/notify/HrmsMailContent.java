package com.coc.docs.notify;

import java.time.LocalDate;
import java.util.List;

/**
 * One HRMS attendance report, parsed from the mail and held only long enough
 * to answer the request that asked for it.
 *
 * <p><b>Never persisted.</b> {@code V21__hrms_notification.sql} keeps the
 * report body out of this database on purpose — it names ~60 colleagues and
 * their working hours — and that still holds. This record is built on a click,
 * serialised to the caller, and dropped. Nothing writes it to a table, a log
 * or a cache, and {@code toString} is left to the record default only because
 * nothing logs one; if that ever changes, this is the type whose fields must
 * not reach a log line.
 *
 * <p>See {@code ADR-042} for the decision that lets it exist at all, and for
 * what was weighed: the platform now shows this data on request, without
 * holding it.
 *
 * @param subject the mail's subject, which is what the feed row already shows
 * @param reportDate the day the report covers, or null when the subject did
 *     not parse — the table is still worth showing without it
 * @param department the heading above the table, e.g. "BeplePay Department".
 *     Blank when the mail had none, which is not an error worth failing on.
 * @param rows one line per person, in the order the mail listed them —
 *     deliberately not re-sorted, because the sender's order is the one the
 *     reader recognises from the email itself
 */
public record HrmsMailContent(
        String subject,
        LocalDate reportDate,
        String department,
        List<Row> rows,
        List<Detail> details,
        String note) {

    public HrmsMailContent {
        subject = subject == null ? "" : subject;
        department = department == null ? "" : department;
        rows = rows == null ? List.of() : List.copyOf(rows);
        details = details == null ? List.of() : List.copyOf(details);
        note = note == null ? "" : note;
    }

    /**
     * The same report carrying only the row naming {@code hrmsName}, for a
     * caller entitled to their own attendance and nobody else's.
     *
     * <p>A null or blank name keeps <b>no</b> rows. That is the unlinked
     * account (V47), and it must not read as "no filter" — an accidental
     * null here would hand a plain {@code user} the whole department, which
     * is the failure this method exists to prevent (NFR-SEC-PII).
     *
     * <p>Matching is case-insensitive and collapses internal whitespace: HRMS
     * writes its own cells and pads them inconsistently, and an admin typing
     * the mapping copies what they see. It goes no further than that — no
     * prefix, initials or edit-distance matching, because a near-miss shows
     * one employee's times to a different employee, and silently showing
     * nothing is a far better failure than silently showing the wrong person.
     *
     * <p>{@code details} and {@code note} are kept, not dropped. A leave or
     * overtime mail has no rows at all and carries its content there; the
     * person it concerns is the one reading it, and stripping that would be
     * scoping by accident rather than on purpose.
     */
    /**
     * The same report carrying only the rows that belong to {@code email} —
     * the caller's own attendance and nobody else's.
     *
     * <p><b>Matched from the address, not from a name somebody typed.</b> The
     * mail identifies a person only by a name cell, and this team's addresses
     * encode that same name: {@code samoeunveth99@gmail.com} against a row
     * reading {@code Veth Sam Oeun}. So the local part is reduced to its
     * letters ({@code samoeunveth}) and compared against the row's name tokens
     * ({@code veth}, {@code sam}, {@code oeun}) in every order they could have
     * been written. A match means the address is exactly those tokens and
     * nothing else.
     *
     * <p><b>Every token must be accounted for.</b> {@code oudompanha} does not
     * match {@code Chamreun Oudompanha} — one of two tokens is not that person
     * identified, it is a coincidence waiting to hit somebody who shares a
     * family name. A partial match shows one employee's clock-in times to
     * another (NFR-SEC-PII), and no rows is a far better failure than the
     * wrong rows.
     *
     * <p>{@code explicitName} — {@code account.hrms_name}, V47 — still wins
     * when set. It is the escape hatch for an address the mail's names cannot
     * be derived from, which is most addresses that are not built this way.
     *
     * <p>{@code details} and {@code note} are kept, not dropped: a leave or
     * overtime mail has no rows at all and carries its content there, and the
     * person reading it is the one it concerns.
     */
    public HrmsMailContent onlyRowsFor(String explicitName, String email) {
        String wanted = normalizeName(explicitName);
        String emailKey = emailKey(email);

        List<Row> mine = rows.stream()
                .filter(row -> wanted.isEmpty()
                        ? matchesEmail(row.name(), emailKey)
                        : normalizeName(row.name()).equals(wanted))
                .toList();
        return new HrmsMailContent(subject, reportDate, department, mine, details, note);
    }

    /**
     * Whether {@code name}'s tokens, in some order, spell {@code emailKey}
     * exactly.
     *
     * <p>Permutations rather than a sorted-letter comparison: sorting the
     * letters would make any anagram match, and "Sen Navy" and "Navy Sen" are
     * the same person while two unrelated names sharing a letter multiset are
     * not. A name of more than five parts is refused rather than permuted —
     * 120 orderings is already generous, and the sixth would be the start of a
     * factorial nobody asked for.
     */
    private static boolean matchesEmail(String name, String emailKey) {
        if (emailKey.isEmpty()) {
            return false;
        }
        List<String> tokens = List.of(normalizeName(name).split(" "));
        if (tokens.isEmpty() || tokens.size() > 5) {
            return false;
        }
        int total = tokens.stream().mapToInt(String::length).sum();
        if (total != emailKey.length()) {
            return false;
        }
        return spellsKey(tokens, "", emailKey);
    }

    /** Depth-first over the orderings, pruning any that already disagrees. */
    private static boolean spellsKey(List<String> remaining, String built, String key) {
        if (remaining.isEmpty()) {
            return built.equals(key);
        }
        if (!key.startsWith(built)) {
            return false;
        }
        for (int i = 0; i < remaining.size(); i++) {
            List<String> rest = new java.util.ArrayList<>(remaining);
            String token = rest.remove(i);
            if (spellsKey(rest, built + token, key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The address reduced to comparable letters: local part only, lower-cased,
     * with digits and punctuation dropped — {@code samoeunveth99@gmail.com}
     * becomes {@code samoeunveth}. Digits are noise a person added to get a
     * free address, never part of their name.
     */
    private static String emailKey(String email) {
        if (email == null) {
            return "";
        }
        int at = email.indexOf('@');
        String local = at < 0 ? email : email.substring(0, at);
        return local.replaceAll("[^A-Za-z]", "").toLowerCase(java.util.Locale.ROOT);
    }

    /** Lower-cased, trimmed, internal runs of whitespace collapsed to one space. */
    private static String normalizeName(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * One label/value pair from the mails that are not attendance reports.
     *
     * <p>HRMS sends more than the daily table: leave and overtime requests
     * accepted or rejected, an account created, a password reset. Those are all
     * the same shape — a heading, four or so labelled fields, a footer — and
     * the feed lists them beside the reports, so the popup has to render them
     * or it says "could not be read" for most of what it is asked to open.
     *
     * <p>Label and value both, rather than a formatted line, because the
     * rendering belongs to the screen: a phone wants them stacked and a desktop
     * wants two columns, and a pre-joined {@code "From: 08-Sep"} can be neither.
     */
    public record Detail(String label, String value) {

        public Detail {
            label = label == null ? "" : label;
            value = value == null ? "" : value;
        }
    }

    /**
     * One person's line.
     *
     * @param name as the mail spells it, never normalised — this is somebody's
     *     name and the sender is the authority on it
     */
    public record Row(String name, Entry yesterdayCheckOut, Entry todayCheckIn) {}

    /**
     * One check, as the mail presents it: what was scheduled, what happened,
     * and what HRMS called the difference.
     *
     * <p>All three are strings, and that is deliberate. {@code scheduled} and
     * {@code actual} are wall-clock labels this platform only ever passes
     * through — parsing them to {@code LocalTime} would mean picking a zone
     * this system does not know and cannot infer, to gain nothing, and would
     * turn HRMS writing "00:00" for "never happened" into either a wrong time
     * or a dropped row.
     *
     * @param status HRMS's own words — "Normal", "Late Check-In", "Missed
     *     Check-Out". Passed through rather than mapped to an enum so a status
     *     this build has never heard of still reaches the screen.
     */
    public record Entry(String scheduled, String actual, String status) {

        public Entry {
            scheduled = scheduled == null ? "" : scheduled;
            actual = actual == null ? "" : actual;
            status = status == null ? "" : status;
        }
    }

    /**
     * Whether anything at all was read out of the mail.
     *
     * <p>Both halves, because a mail is one or the other: an attendance report
     * has rows and no details, a leave request has details and no rows. Only
     * when neither came back has the parser genuinely failed, and only then
     * should the screen say so — checking {@code rows} alone is what made every
     * leave-request mail report itself as unreadable.
     */
    public boolean isEmpty() {
        return rows.isEmpty() && details.isEmpty();
    }

    /** The heading the mail gave itself — a department for a report, "Leave Request" for the rest. */
    public String heading() {
        return department;
    }
}
