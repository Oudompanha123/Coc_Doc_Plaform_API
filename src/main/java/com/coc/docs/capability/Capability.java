package com.coc.docs.capability;

import java.util.Optional;

/**
 * A grant layered on top of whatever {@code AppRole} an account already
 * holds, independent of its rank.
 *
 * <p>Mirrors {@code account.Permission} in shape — a flat enum backed by a
 * database table, resolved fresh per request and never cached in the session
 * row — but answers a different question. A permission is granted through a
 * role ({@code role_permission}); a capability is granted directly to an
 * account ({@code account_capability}) and carries no rank, so granting one
 * can never move an account up or down the {@code app_role} ladder that
 * {@code page.min_role} also depends on.
 *
 * <p><b>Deliberately its own package, not {@code account}.</b> {@code
 * account} is the ladder's home; a capability that lived there would invite
 * the exact coupling this mechanism exists to avoid (ADR-037).
 *
 * <p>Started with one value ({@code developer}); {@code tech_lead} and
 * {@code reviewer} were added by ADR-039 as the "AX Role" choices on the
 * account details screen. All three are AX-Renewal-specific role tags today,
 * surfaced together in the UI, but remain independent, non-exclusive grants —
 * an account may hold more than one. Declared after {@code DEVELOPER} so its
 * ordinal, and anything that iterates {@link #values()}, is undisturbed.
 *
 * <p>// req: ADR-016, ADR-037, ADR-039
 */
public enum Capability {
    DEVELOPER("developer"),
    TECH_LEAD("tech_lead"),
    REVIEWER("reviewer");

    private final String key;

    Capability(String key) {
        this.key = key;
    }

    /** The database form, e.g. {@code developer}. */
    public String key() {
        return key;
    }

    /**
     * The Spring Security authority form, e.g. {@code capability.developer}.
     *
     * <p>Prefixed to guarantee it can never collide with a {@link
     * com.coc.docs.account.Permission#key()} value, and to read unambiguously
     * at a {@code hasAnyAuthority(...)} call site as "a capability, not a
     * role and not a permission".
     */
    public String authority() {
        return "capability." + key;
    }

    /**
     * Parses a key read from {@code account_capability}.
     *
     * <p>Empty rather than a default, for the same reason {@code
     * AppRole#parse}/{@code Permission#parse} give: a row this build does not
     * understand is drift to surface, not a value to guess at.
     */
    public static Optional<Capability> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (Capability c : values()) {
            if (c.key.equalsIgnoreCase(value)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }
}
