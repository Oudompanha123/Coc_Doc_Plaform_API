package com.coc.docs.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code account} rows.
 *
 * <p>// req: FR-AUTH-003, FR-AUTH-005, FR-AUTH-008
 */
@Repository
public class AccountRepository {

    private static final String COLUMNS =
            "id, email, display_name, role, deactivated_at, created_at, updated_at";

    private static final RowMapper<Account> MAPPER = AccountRepository::map;

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Account map(ResultSet rs, int rowNum) throws SQLException {
        String raw = rs.getString("role");
        Optional<AppRole> role = AppRole.parse(raw);
        if (role.isEmpty()) {
            throw new SQLException("account.role holds a value outside the app_role enum: " + raw);
        }
        OffsetDateTime deactivated = rs.getObject("deactivated_at", OffsetDateTime.class);
        return new Account(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("display_name"),
                role.get(),
                deactivated == null ? null : deactivated.toInstant(),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    // -----------------------------------------------------------------------
    // Reads
    // -----------------------------------------------------------------------

    public Optional<Account> findById(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from account where id = ?")
                .param(id)
                .query(MAPPER)
                .optional();
    }

    /**
     * Case-insensitive, because Google returns the address as the user typed it
     * at some point in the past and {@code Editor@example.com} must not become a
     * second account for the same person.
     */
    public Optional<Account> findByEmail(String email) {
        return jdbc.sql("select " + COLUMNS + " from account where lower(email) = lower(?)")
                .param(email)
                .query(MAPPER)
                .optional();
    }

    public List<Account> findAll() {
        return jdbc.sql("select " + COLUMNS + " from account order by email")
                .query(MAPPER)
                .list();
    }

    /**
     * Whether removing {@code excluding} would leave no active super admin.
     *
     * <p>The database holds the same rule as a deferred constraint trigger, which
     * is the control that actually cannot be bypassed. This exists so the API can
     * answer 409 with an explanation rather than surfacing a raised Postgres
     * exception as a 500.
     *
     * <p>// req: UC-004 E-4, FR-AUTH-008
     */
    public boolean isLastActiveSuperAdmin(UUID excluding) {
        Integer remaining = jdbc.sql("""
                        select count(*) from account
                        where role = 'super_admin' and deactivated_at is null and id <> ?
                        """)
                .param(excluding)
                .query(Integer.class)
                .single();
        return remaining == null || remaining == 0;
    }

    // -----------------------------------------------------------------------
    // Writes
    // -----------------------------------------------------------------------

    /**
     * Creates an account for a verified Google identity.
     *
     * <p>Role defaults to {@code user} and is never taken from the sign-in: a new
     * signer-in gets read access and nothing more, and someone with
     * {@code super_admin} has to grant anything above that. This is what stops
     * account creation from being a privilege-escalation path (FR-AUTH-005).
     */
    public Account insert(String email, String displayName, AppRole role) {
        return jdbc.sql("""
                        insert into account (email, display_name, role)
                        values (?, ?, ?::app_role)
                        returning id, email, display_name, role, deactivated_at, created_at, updated_at
                        """)
                .params(email, displayName, role.wire())
                .query(MAPPER)
                .single();
    }

    /** Refreshes the display name from the identity provider on each sign-in. */
    public int updateDisplayName(UUID id, String displayName) {
        return jdbc.sql("""
                        update account set display_name = ?, updated_at = now()
                        where id = ? and display_name <> ?
                        """)
                .params(displayName, id, displayName)
                .update();
    }

    public int updateRole(UUID id, AppRole role) {
        return jdbc.sql("update account set role = ?::app_role, updated_at = now() where id = ?")
                .params(role.wire(), id)
                .update();
    }

    /** Deactivate, never delete: authored content keeps its attribution. */
    public int setDeactivated(UUID id, boolean deactivated) {
        return jdbc.sql("""
                        update account
                        set deactivated_at = case when ? then now() else null end,
                            updated_at = now()
                        where id = ?
                        """)
                .params(deactivated, id)
                .update();
    }
}
