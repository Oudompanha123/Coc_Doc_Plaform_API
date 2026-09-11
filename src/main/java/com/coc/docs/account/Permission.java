package com.coc.docs.account;

import java.util.Optional;

/**
 * What a caller may do, independent of which role granted it.
 *
 * <p>Mirrors the {@code permission} table, the same way {@link AppRole} mirrors
 * the {@code app_role} enum. {@code PermissionTest} asserts the two agree, so a
 * key added to one and forgotten in the other fails a build rather than
 * silently granting nothing — a permission that does not resolve would
 * otherwise read as "this caller may not", which is the failure mode nobody
 * reports because it looks like the system working.
 *
 * <p><b>Permissions, not roles, are the extension point.</b> Adding a role
 * means an {@code app_role} enum value and a rank, which is a schema change and
 * should feel like one. Adding a permission is a row and a constant here.
 *
 * <p>// req: FR-AUTH-005, FR-AUTH-008, ADR-016
 */
public enum Permission {
    CONTENT_READ("content.read"),
    CONTENT_DRAFT_EDIT("content.draft.edit"),
    CONTENT_PUBLISH("content.publish"),
    CONTENT_VALIDATE("content.validate"),
    ACCOUNT_READ("account.read"),
    ACCOUNT_APPROVE("account.approve"),
    ACCOUNT_ROLE_MANAGE("account.role.manage"),
    ACCOUNT_DEACTIVATE("account.deactivate");

    private final String key;

    Permission(String key) {
        this.key = key;
    }

    /** The database and Spring Security authority form, e.g. {@code content.publish}. */
    public String key() {
        return key;
    }

    /**
     * Parses a key read from {@code role_permission}.
     *
     * <p>Empty rather than a default, for the reason {@link AppRole#parse}
     * gives: a row this application does not understand is drift to surface,
     * not a value to guess at.
     */
    public static Optional<Permission> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (Permission p : values()) {
            if (p.key.equalsIgnoreCase(value)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }
}
