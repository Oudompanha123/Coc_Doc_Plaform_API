package com.coc.docs.account;

import java.util.Locale;
import java.util.Optional;

/**
 * The role ladder, mirroring the {@code app_role} Postgres enum.
 *
 * <p>Ordered: {@code guest < user < admin < super_admin}. The ordering is the
 * point — every authorisation check in the system is "is the caller at least
 * X", so the comparison lives here once rather than as a set of role names
 * repeated at each call site (FR-AUTH-005).
 *
 * <p>A role is always resolved server-side from the {@code account} row on
 * every request. It is never read from a client header, cookie or JWT claim,
 * which is what closes TM-003 across the new frontend/backend boundary.
 *
 * <p>// req: FR-AUTH-005, FR-AUTH-006, ADR-011, ADR-012
 */
public enum AppRole {
    GUEST,
    USER,
    ADMIN,
    SUPER_ADMIN;

    /** The wire and database form: lower case. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * True when this role is at or above {@code required}.
     *
     * <p>Relies on declaration order, so the ladder must stay in ascending
     * privilege order. {@link AppRoleTest} asserts that it does, because a
     * reordering would silently invert every authorisation check in the system.
     */
    public boolean isAtLeast(AppRole required) {
        return this.ordinal() >= required.ordinal();
    }

    /**
     * Parses a database or wire value.
     *
     * <p>Returns empty rather than defaulting to {@link #GUEST}. A quiet
     * downgrade on an unrecognised value would be the safe direction, but it
     * would also hide a schema/enum drift that ought to fail loudly at the point
     * it is read.
     */
    public static Optional<AppRole> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (AppRole r : values()) {
            if (r.wire().equalsIgnoreCase(value)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    /** Spring Security authority form, e.g. {@code ROLE_ADMIN}. */
    public String authority() {
        return "ROLE_" + name();
    }
}
