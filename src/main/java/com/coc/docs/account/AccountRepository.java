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
 * <p>// req: FR-AUTH-003, FR-AUTH-005, FR-AUTH-008, ADR-014
 */
@Repository
public class AccountRepository {

    private static final String COLUMNS =
            "id, email, display_name, role, status, deactivated_at, created_at, updated_at";

    /**
     * {@code password_hash} is deliberately absent from {@link #COLUMNS}: every
     * query in this class except {@link #findCredentialByEmailIgnoreCase} must
     * be incapable of reading it. Kept as its own list rather than appended to
     * {@code COLUMNS}, so neither can drift into the other by accident.
     */
    private static final String COLUMNS_WITH_CREDENTIAL = COLUMNS + ", password_hash";

    private static final RowMapper<Account> MAPPER = AccountRepository::map;

    private static final RowMapper<AccountCredential> CREDENTIAL_MAPPER =
            (rs, rowNum) -> new AccountCredential(map(rs, rowNum), rs.getString("password_hash"));

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
                AccountStatus.fromDb(rs.getString("status")),
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
     * Backs the {@code super_admin} approval queue (ADR-014).
     *
     * <p>Ordered oldest first: someone who registered on Monday should not sit
     * behind Friday's registrations.
     */
    public List<Account> findByStatus(AccountStatus status) {
        return jdbc.sql("select " + COLUMNS + " from account where status = ?::account_status order by created_at")
                .param(status.wire())
                .query(MAPPER)
                .list();
    }

    /**
     * The password sign-in path only (ADR-014) — the one query in this class
     * that reads {@code password_hash}.
     *
     * <p>Returns the row even when the account cannot sign in. Deciding that is
     * the caller's job, and it must compare the password regardless, or the
     * response time alone tells an attacker which addresses are registered.
     */
    public Optional<AccountCredential> findCredentialByEmailIgnoreCase(String email) {
        return jdbc.sql("select " + COLUMNS_WITH_CREDENTIAL + " from account where lower(email) = lower(?)")
                .param(email)
                .query(CREDENTIAL_MAPPER)
                .optional();
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
     *
     * <p>Provisioned {@code active}: nobody needs to approve what an
     * administrator's identity provider already vouched for.
     */
    public Account insert(String email, String displayName, AppRole role) {
        return jdbc.sql("""
                        insert into account (email, display_name, role, status)
                        values (?, ?, ?::app_role, 'active'::account_status)
                        returning
                        """ + " " + COLUMNS)
                .params(email, displayName, role.wire())
                .query(MAPPER)
                .single();
    }

    /**
     * Creates a self-registered account awaiting review (ADR-014).
     *
     * <p>Role and status are literals here, never parameters. A registration
     * request that could choose either would be a self-service route to
     * {@code super_admin}, which is TM-025 — the second-highest threat on the
     * project. The caller supplies an email, a name and a hash; nothing else it
     * sends can reach this statement.
     */
    public Account insertPendingRegistration(String email, String displayName, String passwordHash) {
        return jdbc.sql("""
                        insert into account (email, display_name, password_hash, role, status)
                        values (?, ?, ?, 'user'::app_role, 'pending'::account_status)
                        returning
                        """ + " " + COLUMNS)
                .params(email, displayName, passwordHash)
                .query(MAPPER)
                .single();
    }

    /** A {@code super_admin}'s approve or reject on a pending registration (ADR-014). */
    public int updateStatus(UUID id, AccountStatus status) {
        return jdbc.sql("update account set status = ?::account_status, updated_at = now() where id = ?")
                .params(status.wire(), id)
                .update();
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
