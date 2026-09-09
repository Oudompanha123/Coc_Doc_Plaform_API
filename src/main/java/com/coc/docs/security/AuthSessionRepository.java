package com.coc.docs.security;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Plain JDBC against {@code app_session} (V2 migration). Package-private:
 * only {@link SessionService} should ever touch a session row directly.
 */
@Repository
class AuthSessionRepository {

    private final JdbcTemplate jdbcTemplate;

    AuthSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void insert(String tokenHash, UUID accountId, Instant expiresAt) {
        jdbcTemplate.update(
                "insert into app_session (token_hash, account_id, expires_at) values (?, ?, ?)",
                tokenHash,
                accountId,
                Timestamp.from(expiresAt));
    }

    /** Empty if the token is unknown, revoked (deleted), or past its TTL. */
    Optional<UUID> findActiveAccountId(String tokenHash) {
        List<UUID> rows = jdbcTemplate.query(
                "select account_id from app_session where token_hash = ? and expires_at > now()",
                (rs, rowNum) -> (UUID) rs.getObject("account_id"),
                tokenHash);
        return rows.stream().findFirst();
    }

    void touch(String tokenHash) {
        jdbcTemplate.update("update app_session set last_seen_at = now() where token_hash = ?", tokenHash);
    }

    /** Real server-side revocation (FR-AUTH-007) — the row is simply gone. */
    void delete(String tokenHash) {
        jdbcTemplate.update("delete from app_session where token_hash = ?", tokenHash);
    }
}
