package com.coc.docs.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What counts as a usable email address (ADR-088).
 *
 * <p><b>Why anything beyond {@code @Email}.</b> Jakarta's built-in constraint
 * accepts {@code qqq@qqq} — no dot, no top-level domain, nowhere to deliver.
 * That is not a defect in the annotation: it implements the grammar, and
 * {@code user@localhost} is a valid address in it. It is the wrong rule for a
 * system whose addresses must receive a registration code over the public
 * internet, which is the only reason this file exists.
 *
 * <p><b>The authoritative copy.</b> {@code frontend/lib/auth/emailPolicy.ts}
 * mirrors it so the form can complain as somebody types, but that one is
 * advisory (CONST-TECH-07) — this is the one a write has to pass. Matching case
 * lists in both test suites fail if a rule changes on one side only.
 *
 * <p><b>Applied where an address is accepted, never where one is presented.</b>
 * Registration, invitation and the reset request go through this; signing in
 * does not. An account created under a looser rule must still be able to log
 * in, for the same reason {@link PasswordPolicy} is kept off
 * {@code PasswordLoginRequest}.
 *
 * <p><b>Deliberately not stricter than this.</b> Plus-addressing
 * ({@code a+tag@example.com}), dots, hyphens and long top-level domains are all
 * accepted, because the expensive failure here is rejecting somebody's real
 * address — they cannot argue with a form, and they have no way to discover
 * which character it objected to. Delivery is the real test; this only rules
 * out what certainly cannot be delivered.
 *
 * <p>// req: NFR-SEC-AUTH, FR-AUTH-002, ADR-088
 */
public final class EmailPolicy {

    /** RFC 5321 §4.5.3.1.1: the local part may not exceed 64 octets. */
    public static final int MAX_LOCAL_LENGTH = 64;

    /** RFC 5321 §4.5.3.1.3: a path may not exceed 256 octets, leaving 254 for the address. */
    public static final int MAX_LENGTH = 254;

    /**
     * The local part: the printable characters RFC 5322 permits unquoted, in
     * dot-separated runs.
     *
     * <p>Quoted local parts ({@code "odd name"@example.com}) are valid and not
     * accepted here. They are vanishingly rare, many providers refuse them, and
     * supporting them would widen what reaches the rest of the system for no
     * practical gain.
     */
    private static final String ATOM = "[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+";

    private static final Pattern LOCAL = Pattern.compile("^" + ATOM + "(?:\\." + ATOM + ")*$");

    /**
     * The domain: dot-separated labels, each starting and ending
     * alphanumerically, with a final label of at least two letters.
     *
     * <p>The last part is what rejects {@code qqq@qqq} and {@code name@localhost}:
     * an address this system must be able to email needs a real top-level
     * domain. Letters only there, so {@code name@192.168.1.1} is refused too —
     * an IP-literal address is valid in the grammar and never what somebody
     * typing into a registration form intended.
     */
    private static final Pattern DOMAIN = Pattern.compile(
            "^(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}$");

    private EmailPolicy() {}

    /**
     * Every rule this address breaks.
     *
     * <p>Each message names the specific thing that is wrong, because "invalid
     * email" tells somebody staring at an address that looks right to them
     * nothing at all about where to look.
     *
     * @return an empty list when the address is acceptable
     */
    public static List<String> violations(String email) {
        List<String> problems = new ArrayList<>();
        if (email == null || email.isBlank()) {
            problems.add("Enter an email address.");
            return problems;
        }

        String value = email.trim();
        if (!value.equals(email)) {
            // Trimmed above and still compared, because a pasted address often
            // carries a trailing space and silently accepting it stores an
            // address that will not match on the next lookup.
            problems.add("Remove the spaces around the address.");
        }
        if (value.contains(" ")) {
            problems.add("An email address cannot contain a space.");
        }

        int at = value.lastIndexOf('@');
        if (at < 0) {
            problems.add("Include an @, as in name@example.com.");
            return problems;
        }
        if (value.indexOf('@') != at) {
            problems.add("An email address can only contain one @.");
            return problems;
        }

        String local = value.substring(0, at);
        String domain = value.substring(at + 1);

        if (local.isEmpty()) {
            problems.add("Add the part before the @, as in name@example.com.");
        } else if (local.length() > MAX_LOCAL_LENGTH) {
            problems.add("The part before the @ is too long.");
        } else if (!LOCAL.matcher(local).matches()) {
            problems.add("The part before the @ contains something that is not allowed.");
        }

        if (domain.isEmpty()) {
            problems.add("Add the part after the @, as in name@example.com.");
        } else if (!domain.contains(".")) {
            // The common real mistake, and the one Jakarta's @Email allows.
            problems.add("Add a domain ending, such as .com.");
        } else if (!DOMAIN.matcher(domain).matches()) {
            problems.add("The part after the @ is not a valid domain.");
        }

        if (value.length() > MAX_LENGTH) {
            problems.add("That email address is too long.");
        }
        return problems;
    }

    /** Whether this address may be stored. */
    public static boolean isAcceptable(String email) {
        return violations(email).isEmpty();
    }
}
