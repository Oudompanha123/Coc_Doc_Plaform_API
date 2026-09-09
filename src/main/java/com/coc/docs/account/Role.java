package com.coc.docs.account;

import java.util.Locale;

/**
 * The role ladder: {@code guest < user < admin < super_admin}. // req: FR-AUTH-005
 *
 * <p>Mirrors the {@code app_role} Postgres enum in {@code V1__initial_schema.sql}
 * exactly — this is the Java side of that single source of truth, not an
 * independent definition.
 */
public enum Role {
    GUEST,
    USER,
    ADMIN,
    SUPER_ADMIN;

    /** Parses the Postgres enum's text value (e.g. {@code "super_admin"}). */
    public static Role fromDb(String dbValue) {
        return Role.valueOf(dbValue.trim().toUpperCase(Locale.ROOT));
    }

    /** The Spring Security authority this role grants, e.g. {@code ROLE_ADMIN}. */
    public String authority() {
        return "ROLE_" + name();
    }
}
