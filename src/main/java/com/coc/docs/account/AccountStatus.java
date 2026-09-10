package com.coc.docs.account;

import java.util.Locale;

/**
 * The registration/approval lifecycle for an account (ADR-014, revision of
 * ADR-008). Independent of {@link Account#deactivated()}: {@code status}
 * answers "was this registration ever approved to sign in at all";
 * deactivation answers "was an approved account later turned off".
 *
 * <p>Mirrors the {@code account_status} Postgres enum in
 * {@code V3__account_registration.sql} exactly — this is the Java side of
 * that single source of truth, not an independent definition.
 *
 * // req: FR-AUTH-003, ADR-014
 */
public enum AccountStatus {
    PENDING,
    ACTIVE,
    REJECTED;

    /** Parses the Postgres enum's text value (e.g. {@code "pending"}). */
    public static AccountStatus fromDb(String dbValue) {
        return AccountStatus.valueOf(dbValue.trim().toUpperCase(Locale.ROOT));
    }

    /** The Postgres enum literal this value is stored as, e.g. {@code "active"}. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
