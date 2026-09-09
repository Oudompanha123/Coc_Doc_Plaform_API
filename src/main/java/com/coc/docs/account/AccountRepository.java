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
            "select id, email, display_name, role, deactivated_at from account";

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

    private static Account mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Account(
                (UUID) rs.getObject("id"),
                rs.getString("email"),
                rs.getString("display_name"),
                Role.fromDb(rs.getString("role")),
                rs.getTimestamp("deactivated_at") != null);
    }
}
