package com.coc.docs.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code password_reset_token} rows (ADR-017).
 *
 * <p>// req: ADR-017
 */
@Repository
public class PasswordResetTokenRepository {

    private final JdbcClient jdbc;

    public PasswordResetTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static PasswordResetToken map(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime used = rs.getObject("used_at", OffsetDateTime.class);
        return new PasswordResetToken(
                rs.getBytes("token_hash"),
                rs.getObject("account_id", UUID.class),
                rs.getObject("issued_at", OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                used == null ? null : used.toInstant());
    }

    public void insert(byte[] tokenHash, UUID accountId, Instant expiresAt) {
        jdbc.sql("""
                        insert into password_reset_token (token_hash, account_id, expires_at)
                        values (?, ?, ?)
                        """)
                .params(tokenHash, accountId, OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                .update();
    }

    /**
     * Looks up a token by its hash, whatever its state.
     *
     * <p>Usability (spent, expired) is checked by the caller rather than
     * filtered here, so a presented-but-dead token can be distinguished from
     * one that never existed in the logs — the same split {@link
     * SessionRepository#find} makes, for the same reason.
     */
    public Optional<PasswordResetToken> find(byte[] tokenHash) {
        return jdbc.sql("""
                        select token_hash, account_id, issued_at, expires_at, used_at
                        from password_reset_token
                        where token_hash = ?
                        """)
                .param(tokenHash)
                .query(PasswordResetTokenRepository::map)
                .optional();
    }

    /** Marks a token spent. A second presentation of the same token then finds it unusable. */
    public int markUsed(byte[] tokenHash) {
        return jdbc.sql("""
                        update password_reset_token set used_at = now()
                        where token_hash = ? and used_at is null
                        """)
                .param(tokenHash)
                .update();
    }

    /**
     * Removes rows whose expiry is past. Table hygiene, not a security
     * control — an expired token is already refused by {@link
     * PasswordResetToken#isUsable}, which checks the instant directly.
     */
    public int deleteExpiredBefore(Instant cutoff) {
        return jdbc.sql("delete from password_reset_token where expires_at < ?")
                .param(OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .update();
    }
}
