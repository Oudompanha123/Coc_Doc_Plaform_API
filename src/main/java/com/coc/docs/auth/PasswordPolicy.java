package com.coc.docs.auth;

import java.util.ArrayList;
import java.util.List;

/**
 * What counts as a strong enough password (ADR-087).
 *
 * <p><b>The authoritative copy.</b> {@code frontend/lib/auth/passwordPolicy.ts}
 * mirrors these rules so the form can complain before a round trip, but that
 * one is advisory (CONST-TECH-07) — this is the one a write has to pass. The
 * two are kept in step by {@code PasswordPolicyParityTest}, which fails if a
 * rule changes on one side only.
 *
 * <p><b>Applied where a password is set, never where one is presented.</b>
 * Registration, reset and change go through this; signing in does not. An
 * account whose password predates this policy must still be able to log in —
 * and then be told to change it — rather than being locked out by a rule
 * introduced after it was chosen.
 *
 * <p><b>On composition rules.</b> NIST SP 800-63B argues length matters more
 * than mandatory character classes, and would prefer a long passphrase checked
 * against a breach list. This project has no breach list and the PM asked for a
 * strong pattern, so the classes are here — but the length floor is 12 rather
 * than the 8 it replaces, because that is the part that actually buys
 * resistance to guessing. If a breach-list check is ever added, the classes
 * should be reconsidered rather than kept out of habit.
 *
 * <p>// req: NFR-SEC-AUTH, FR-AUTH-002, ADR-087
 */
public final class PasswordPolicy {

    /**
     * Twelve, not eight.
     *
     * <p>Eight was the previous floor and admits things like {@code Passw0rd!}
     * — four character classes and a dictionary word, which is exactly what a
     * composition rule fails to catch and length does.
     */
    public static final int MIN_LENGTH = 12;

    /**
     * An upper bound at all, because bcrypt silently truncates past 72 bytes:
     * two different long passwords can hash the same, so anything beyond it is
     * decoration that looks like security. 200 keeps the existing contract and
     * stays well clear of the input sizes a request body should carry.
     */
    public static final int MAX_LENGTH = 200;

    private PasswordPolicy() {}

    /**
     * Every rule this password breaks, in the order a person should fix them.
     *
     * <p>All of them, not the first: a form that reveals one requirement at a
     * time turns choosing a password into a guessing game, and people respond
     * by appending {@code 1!} until it stops complaining.
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
        if (password.chars().noneMatch(Character::isLowerCase)) {
            problems.add("Include a lowercase letter.");
        }
        if (password.chars().noneMatch(Character::isUpperCase)) {
            problems.add("Include an uppercase letter.");
        }
        if (password.chars().noneMatch(Character::isDigit)) {
            problems.add("Include a number.");
        }
        if (password.chars().noneMatch(PasswordPolicy::isSymbol)) {
            problems.add("Include a symbol, such as ! ? @ or #.");
        }
        return problems;
    }

    /** Whether this password may be stored. */
    public static boolean isAcceptable(String password) {
        return violations(password).isEmpty();
    }

    /**
     * Anything that is not a letter, a digit or whitespace.
     *
     * <p>Defined by exclusion so that a symbol from any script counts. A list
     * of allowed punctuation would quietly reject somebody whose keyboard
     * produces a different dash, and they would have no way to know why.
     */
    private static boolean isSymbol(int codePoint) {
        return !Character.isLetterOrDigit(codePoint) && !Character.isWhitespace(codePoint);
    }
}
