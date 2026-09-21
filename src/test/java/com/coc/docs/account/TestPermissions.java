package com.coc.docs.account;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * What each role grants, mirrored for tests that have no database.
 *
 * <p>The authoritative copy is {@code V4__roles_and_permissions.sql}. This is a
 * mirror, and a mirror nobody checks is worse than no mirror at all — so
 * {@link PermissionCatalogTest} parses that migration and fails if the two ever
 * disagree. Add a permission to the seed and forget this map, and the build
 * says so.
 *
 * <p>// req: FR-AUTH-005, ADR-016
 */
public final class TestPermissions {

    private TestPermissions() {}

    private static final Map<AppRole, Set<Permission>> BY_ROLE = Map.of(
            AppRole.GUEST,
            EnumSet.noneOf(Permission.class),
            AppRole.USER,
            EnumSet.of(Permission.CONTENT_READ),
            AppRole.DEVELOPER,
            EnumSet.of(Permission.CONTENT_READ),
            AppRole.REVIEWER,
            EnumSet.of(Permission.CONTENT_READ),
            AppRole.LEAD,
            EnumSet.of(Permission.CONTENT_READ),
            AppRole.ADMIN,
            EnumSet.of(
                    Permission.CONTENT_READ,
                    Permission.CONTENT_DRAFT_EDIT,
                    Permission.CONTENT_PUBLISH,
                    Permission.CONTENT_VALIDATE),
            AppRole.SUPER_ADMIN,
            EnumSet.of(
                    Permission.CONTENT_READ,
                    Permission.CONTENT_DRAFT_EDIT,
                    Permission.CONTENT_PUBLISH,
                    Permission.CONTENT_VALIDATE,
                    Permission.ACCOUNT_READ,
                    Permission.ACCOUNT_APPROVE,
                    Permission.ACCOUNT_ROLE_MANAGE,
                    Permission.ACCOUNT_DEACTIVATE));

    /** Everything a single role grants. */
    public static Set<Permission> of(AppRole role) {
        return BY_ROLE.getOrDefault(role, EnumSet.noneOf(Permission.class));
    }

    /** The union across a set of roles, which is what a real caller holds. */
    public static Set<Permission> of(Set<AppRole> roles) {
        Set<Permission> union = EnumSet.noneOf(Permission.class);
        roles.forEach(role -> union.addAll(of(role)));
        return union;
    }
}
