package com.coc.docs.auth;

import java.util.ArrayList;
import java.util.List;

/**
 * What counts as an acceptable password (ADR-087).
 *
 * <p><b>The authoritative copy.</b> {@code frontend/lib/auth/passwordPolicy.ts}
 * mirrors this so the form can complain before a round trip, but that one is
 * advisory (CONST-TECH-07) — this is the one a write has to pass. The two are
 * kept in step by matching case lists in both test suites, which fail if a rule
 * changes on one side only.
 *
 * <p><b>Applied where a password is set, never where one is presented.</b>
 * Registration, reset and change go through this; signing in does not. An
 * account whose password predates a change to this policy must still be able
 * to log in — and then be told to change it — rather than being locked out by
 * a rule introduced after it was chosen.
 *
 * <p><b>Length only, no composition rules.</b> NIST SP 800-63B §5.1.1.2 sets
 * eight characters as the floor for a user-chosen secret and says verifiers
 * SHOULD NOT require particular character classes: in practice they push people
 * toward predictable substitutions like {@code Password1!} and toward reusing
 * the one password that satisfies every site's rules.
 *
 * <p>This briefly required twelve characters and four character classes
 * (ADR-087 v1.0) and was reverted to the standard's shape on the PM's
 * instruction. What that gives up is length, which is the part that actually
 * resists guessing — ADR-087 §4.2 records it, and §4.3 names the follow-up
 * that would be worth more than any of this: a breach-list check.
 *
 * <p>// req: NFR-SEC-AUTH, FR-AUTH-002, ADR-087
 */
public final class PasswordPolicy {

    /** The floor NIST SP 800-63B sets for a user-chosen secret. */
    public static final int MIN_LENGTH = 8;

    /**
     * An upper bound at all, because bcrypt silently truncates past 72 bytes:
     * two different long passwords can hash the same, so anything beyond it is
     * decoration that looks like security. 200 keeps the existing contract and
     * stays well clear of the input sizes a request body should carry.
     */
    public static final int MAX_LENGTH = 200;

    private PasswordPolicy() {}

    /**
     * Every rule this password breaks.
     *
     * <p>A list rather than a boolean, and all of them rather than the first,
     * because the frontend renders the same strings — and because a form that
     * reveals one requirement at a time teaches people to append characters
     * until the complaining stops. With one rule left that matters less than
     * it did, but the shape is kept so adding a rule does not mean changing
     * every caller.
     *
     * @return an empty list when the password is acceptable
     */
    public static List<String> violations(String password) {
        List<String> problems = new ArrayList<>();
        if (password == null || password.isEmpty()) {
            problems.add("Enter a password.");
            return problems;
        }
        if (password.length() < MIN_LENGTH) {
            problems.add("Use at least " + MIN_LENGTH + " characters.");
        }
        if (password.length() > MAX_LENGTH) {
            problems.add("Use at most " + MAX_LENGTH + " characters.");
        }
        return problems;
    }

    /** Whether this password may be stored. */
    public static boolean isAcceptable(String password) {
        return violations(password).isEmpty();
    }
}
