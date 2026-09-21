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
 * <p>{@code DEVELOPER}, {@code REVIEWER} and {@code LEAD} (ADR-039) sit
 * strictly between {@code USER} and {@code ADMIN} — real, assignable roles.
 * {@code DEVELOPER} retires the old rank-independent {@code developer}
 * capability (ADR-037/038, revised by ADR-039): every account that held that
 * capability now holds this role instead (migrated once, {@code V31}).
 *
 * <p>{@code DEVELOPER} is the one role here checked by <b>exact membership</b>
 * ({@link #exactAuthority()}), not rank — {@code SecurityConfig}'s AX Renewal
 * confirm/reopen/start-implementation gate must keep admitting a plain
 * account that specifically holds {@code DEVELOPER} while still refusing an
 * {@code ADMIN} (or {@code LEAD}/{@code REVIEWER}) who does not additionally
 * hold it, exactly as the retired capability did. {@link #isAtLeast} cannot
 * express that — an {@code ADMIN} is always "at least developer" by rank —
 * so that one route deliberately does not use {@link #authority()}.
 *
 * <p>{@code REVIEWER} and {@code LEAD} carry no {@code role_permission} grant
 * beyond {@code content.read} yet, and every {@code /api/v1/admin/**} route
 * still floors at {@code ADMIN} (unchanged) — so today they exist as real,
 * orderable roles without yet unlocking any action a plain {@code USER}
 * could not already do. What (if anything) beyond that they should unlock is
 * left for a future decision once a concrete feature needs it, the same
 * deferral ADR-037 already made once for capability-to-permission mapping.
 *
 * <p>A role is always resolved server-side from the {@code account} row on
 * every request. It is never read from a client header, cookie or JWT claim,
 * which is what closes TM-003 across the new frontend/backend boundary.
 *
 * <p>// req: FR-AUTH-005, FR-AUTH-006, ADR-011, ADR-012, ADR-039
 */
public enum AppRole {
    GUEST,
    USER,
    DEVELOPER,
    REVIEWER,
    LEAD,
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

    /** Spring Security authority form, e.g. {@code ROLE_ADMIN}. Rank-based — see this class's own Javadoc. */
    public String authority() {
        return "ROLE_" + name();
    }

    /**
     * Spring Security authority form for <b>exact</b> membership, e.g.
     * {@code role.exact.developer} — true only for an account that literally
     * holds this role, regardless of rank. Use this instead of {@link
     * #authority()} for a check that must not be satisfied merely by
     * out-ranking the role (see {@link #DEVELOPER}'s own note above).
     */
    public String exactAuthority() {
        return "role.exact." + wire();
    }
}
