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
 * Reads and writes {@code mfa_login_challenge} rows (ADR-028).
 *
 * <p>// req: FR-AUTH-009, ADR-028
 */
@Repository
public class MfaLoginChallengeRepository {

    private final JdbcClient jdbc;

    public MfaLoginChallengeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static MfaLoginChallenge map(ResultSet rs, int rowNum) throws SQLException {
        return new MfaLoginChallenge(
                rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getBytes("token_hash"),
                rs.getObject("issued_at", OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant());
    }

    public MfaLoginChallenge insert(UUID accountId, byte[] tokenHash, Instant expiresAt) {
        UUID id = jdbc.sql("""
                        insert into mfa_login_challenge (account_id, token_hash, expires_at)
                        values (?, ?, ?)
                        returning id
                        """)
                .params(accountId, tokenHash, OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                .query(UUID.class)
                .single();

        return find(tokenHash)
                .orElseThrow(() -> new IllegalStateException(
                        "mfa_login_challenge row " + id + " vanished immediately after insert"));
    }

    public Optional<MfaLoginChallenge> find(byte[] tokenHash) {
        return jdbc.sql("select id, account_id, token_hash, issued_at, expires_at from mfa_login_challenge where token_hash = ?")
                .param(tokenHash)
                .query(MfaLoginChallengeRepository::map)
                .optional();
    }

    /** Spent, by a successful verify, or explicitly abandoned. */
    public int delete(byte[] tokenHash) {
        return jdbc.sql("delete from mfa_login_challenge where token_hash = ?").param(tokenHash).update();
    }

    /**
     * Removes rows whose expiry is past. Table hygiene, not a security
     * control — an expired row is already refused by {@link
     * MfaLoginChallenge#isUsable}, which checks the instant directly.
     */
    public int deleteExpiredBefore(Instant cutoff) {
        return jdbc.sql("delete from mfa_login_challenge where expires_at < ?")
                .param(OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .update();
    }
}
