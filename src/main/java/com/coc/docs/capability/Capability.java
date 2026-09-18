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
 * <p>// req: ADR-016, ADR-037
 */
public enum Capability {
    DEVELOPER("developer");

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
