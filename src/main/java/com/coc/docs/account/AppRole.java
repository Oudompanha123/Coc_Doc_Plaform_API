package com.coc.docs.account;

import java.util.Locale;
import java.util.Optional;

/**
 * The role ladder, mirroring the {@code app_role} Postgres enum.
 *
 * <p>Ordered: {@code guest < user < developer < reviewer < lead < admin <
 * super_admin}. The ordering is the point — every authorisation check in the
 * system is "is the caller at least X", so the comparison lives here once
 * rather than as a set of role names repeated at each call site
 * (FR-AUTH-005).
 *
 * <p>{@code DEVELOPER}, {@code REVIEWER} and {@code LEAD} (ADR-040) sit
 * strictly between {@code USER} and {@code ADMIN} — real, assignable roles.
 * {@code DEVELOPER} retired the old rank-independent {@code developer}
 * capability (migrated once, {@code V31}).
 *
 * <p><b>None of the three unlock anything in the admin app</b> (ADR-086,
 * superseding ADR-040 §confirm-floor, ADR-068 and ADR-071): a PM decision on
 * 2026-09-29 retired Developer's AX Renewal confirm/reopen/start-implementation
 * carve-out and Reviewer/Lead's Overview/Settings/AX-Renewal-read-and-comment
 * one. {@code SecurityConfig} now gates every admin-app route with {@link
 * #ADMIN_APP_ROLE_NAMES} (an allow-list, not a rank floor — {@link #isAtLeast}
 * cannot express "refuse everyone between {@code USER} and {@code ADMIN}",
 * since that is precisely the band these three roles occupy). {@link
 * #exactAuthority()} is kept as infrastructure ({@code
 * SessionAuthenticationFilter} still grants it) even though no current route
 * reads it, the same as any other capability the next feature might need.
 *
 * <p>A role is always resolved server-side from the {@code account} row on
 * every request. It is never read from a client header, cookie or JWT claim,
 * which is what closes TM-003 across the new frontend/backend boundary.
 *
 * <p>// req: FR-AUTH-005, FR-AUTH-006, ADR-011, ADR-012, ADR-086
 */
public enum AppRole {
    GUEST,
    USER,
    DEVELOPER,
    REVIEWER,
    LEAD,
    ADMIN,
    SUPER_ADMIN;

    /**
     * The only roles that may use the admin app at all (2026-09-29 PM
     * decision, ADR-086, superseding ADR-040/ADR-068/ADR-071's Developer/
     * Reviewer/Lead carve-outs). An allow-list rather than a rank floor,
     * because {@code isAtLeast} can no longer express this: {@code
     * DEVELOPER}, {@code REVIEWER} and {@code LEAD} all sit between {@code
     * USER} and {@code ADMIN} in rank, and every one of them is now meant to
     * be refused, not merely out-ranked.
     *
     * <p>Spring Security's {@code hasAnyRole} wants bare names (no {@code
     * ROLE_} prefix) — this is already in that form, so callers pass it
     * straight through, e.g. {@code .hasAnyRole(AppRole.ADMIN_APP_ROLE_NAMES)}.
     */
    public static final String[] ADMIN_APP_ROLE_NAMES = {ADMIN.name(), SUPER_ADMIN.name()};

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

    /** Spring Security authority form, e.g. {@code ROLE_ADMIN}. Rank-based — see this class's own Javadoc. */
    public String authority() {
        return "ROLE_" + name();
    }

    /**
     * Spring Security authority form for <b>exact</b> membership, e.g.
     * {@code role.exact.developer} — true only for an account that literally
     * holds this role, regardless of rank. Use this instead of {@link
     * #authority()} for a check that must not be satisfied merely by
     * out-ranking the role (see this class's own Javadoc for why no current
     * route needs that distinction).
     */
    public String exactAuthority() {
        return "role.exact." + wire();
    }
}
