package com.coc.docs.account;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the role catalogue — what each role is, and what it grants.
 *
 * <p>Read-only, deliberately. Roles are the four {@code app_role} enum values
 * and their permissions are seeded by migration (ADR-016); adding one is a
 * schema change that should feel like a schema change, not a row someone
 * creates in a screen at 5pm. This exists so the administration UI can show
 * what a role means rather than leaving an administrator to infer it from the
 * name.
 *
 * <p>// req: FR-AUTH-005, FR-AUTH-008, ADR-016
 */
@Repository
public class RoleRepository {

    /** One role, with everything it grants and how many accounts hold it. */
    public record RoleDetail(AppRole role, int rank, String description, List<Permission> permissions, int accountCount) {}

    private final JdbcClient jdbc;

    public RoleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every role, lowest rank first — the order the ladder reads in.
     *
     * <p>One query. The permissions are aggregated and the holder count is a
     * scalar subquery, rather than three round trips or a count per role.
     */
    public List<RoleDetail> findAll() {
        return jdbc.sql("""
                        select r.key,
                               r.rank,
                               r.description,
                               coalesce(
                                 array_agg(rp.permission_key) filter (where rp.permission_key is not null),
                                 '{}'
                               ) as permissions,
                               (select count(*) from account_role ar where ar.role_key = r.key) as account_count
                        from role r
                        left join role_permission rp on rp.role_key = r.key
                        group by r.key, r.rank, r.description
                        order by r.rank
                        """)
                .query(RoleRepository::map)
                .list();
    }

    private static RoleDetail map(ResultSet rs, int rowNum) throws SQLException {
        String key = rs.getString("key");
        AppRole role = AppRole.parse(key)
                .orElseThrow(() -> new SQLException("role.key holds a value outside the app_role enum: " + key));

        List<Permission> permissions = new ArrayList<>();
        Array array = rs.getArray("permissions");
        if (array != null) {
            for (String permissionKey : (String[]) array.getArray()) {
                // Unknown keys are skipped rather than fatal, matching how the
                // caller's own permissions resolve: a permission this build has
                // no constant for cannot be enforced anyway, and refusing to
                // render the roles screen over it would be a worse trade.
                Permission.parse(permissionKey).ifPresent(permissions::add);
            }
        }
        permissions.sort(java.util.Comparator.comparing(Permission::key));

        return new RoleDetail(
                role,
                rs.getInt("rank"),
                rs.getString("description"),
                List.copyOf(permissions),
                rs.getInt("account_count"));
    }
}
