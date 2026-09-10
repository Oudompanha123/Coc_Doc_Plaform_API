package com.coc.docs.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Plain JDBC, not JPA — matches ADR-003 ("the schema is jsonb-heavy and
 * hand-tuned; an ORM would fight it for no benefit at this scale") and, for
 * this table specifically, there is no jsonb here at all to justify one.
 */
@Repository
public class AccountRepository {

    private static final String SELECT_COLUMNS =
            "select id, email, display_name, role, status, deactivated_at from account";

    private final JdbcTemplate jdbcTemplate;

    public AccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Matches the way UC-004 describes sign-in: identity is matched to an
     * already-provisioned account by email. Case-insensitive because Google
     * account emails are effectively case-insensitive in practice, and the
     * super admin who typed the address at provisioning time may not have
     * matched Google's own casing exactly.
     */
    public Optional<Account> findByEmailIgnoreCase(String email) {
        List<Account> rows = jdbcTemplate.query(
                SELECT_COLUMNS + " where lower(email) = lower(?)", AccountRepository::mapRow, email);
        return rows.stream().findFirst();
    }

    public Optional<Account> findById(UUID id) {
        List<Account> rows =
                jdbcTemplate.query(SELECT_COLUMNS + " where id = ?", AccountRepository::mapRow, id);
        return rows.stream().findFirst();
    }

    /** Backs {@code GET /api/admin/accounts/pending} (ADR-014). */
    public List<Account> findByStatus(AccountStatus status) {
        return jdbcTemplate.query(
                SELECT_COLUMNS + " where status = ?::account_status order by created_at",
                AccountRepository::mapRow,
                status.wire());
    }

    /**
     * The password sign-in path only (ADR-014) — the only query in this
     * class that ever reads {@code password_hash}.
     */
    public Optional<AccountCredential> findCredentialByEmailIgnoreCase(String email) {
        List<AccountCredential> rows = jdbcTemplate.query(
                SELECT_COLUMNS + ", password_hash from account where lower(email) = lower(?)",
                AccountRepository::mapCredentialRow,
                email);
        return rows.stream().findFirst();
    }

    /**
     * Creates a self-registered account awaiting review (ADR-014). Role and
     * status are never taken from the caller — always {@code user} /
     * {@code pending} — so registering is never a privilege-escalation or
     * self-activation path (FR-AUTH-005).
     */
    public Account insertPendingRegistration(String email, String displayName, String passwordHash) {
        return jdbcTemplate.queryForObject(
                "insert into account (email, display_name, password_hash, status) "
                        + "values (?, ?, ?, 'pending') "
                        + "returning id, email, display_name, role, status, deactivated_at",
                AccountRepository::mapRow,
                email,
                displayName,
                passwordHash);
    }

    /** Moves a registration from {@code pending} to {@code active} or {@code rejected} (ADR-014). */
    public int updateStatus(UUID id, AccountStatus status) {
        return jdbcTemplate.update(
                "update account set status = ?::account_status, updated_at = now() where id = ?",
                status.wire(),
                id);
    }

    private static Account mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Account(
                (UUID) rs.getObject("id"),
                rs.getString("email"),
                rs.getString("display_name"),
                Role.fromDb(rs.getString("role")),
                AccountStatus.fromDb(rs.getString("status")),
                rs.getTimestamp("deactivated_at") != null);
    }

    private static AccountCredential mapCredentialRow(ResultSet rs, int rowNum) throws SQLException {
        return new AccountCredential(mapRow(rs, rowNum), rs.getString("password_hash"));
    }
}
