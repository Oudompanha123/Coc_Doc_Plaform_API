package com.coc.docs.claudeusage;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads and writes {@code claude_usage_cli_authorization} rows (V51, ADR-060). */
@Repository
public class ClaudeUsageCliAuthorizationRepository {

    private final JdbcClient jdbc;

    public ClaudeUsageCliAuthorizationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** What a consumed code was bound to at approval time. */
    public record ConsumedCode(UUID accountId, String codeChallenge, String device) {}

    public UUID insert(UUID accountId, String codeHash, String codeChallenge, String device, Instant expiresAt) {
        return jdbc.sql("""
                        insert into claude_usage_cli_authorization (account_id, code_hash, code_challenge, device, expires_at)
                        values (?, ?, ?, ?, ?)
                        returning id
                        """)
                .params(accountId, codeHash, codeChallenge, device, OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                .query(UUID.class)
                .single();
    }

    /**
     * Marks the code used and returns what it was bound to — in one statement,
     * so two concurrent exchanges of the same code cannot both succeed. Empty
     * when the code is unknown, already used, or expired.
     */
    public Optional<ConsumedCode> consume(String codeHash) {
        return jdbc.sql("""
                        update claude_usage_cli_authorization set used_at = now()
                        where code_hash = ? and used_at is null and expires_at > now()
                        returning account_id, code_challenge, device
                        """)
                .param(codeHash)
                .query((rs, n) -> new ConsumedCode(
                        rs.getObject("account_id", UUID.class), rs.getString("code_challenge"), rs.getString("device")))
                .optional();
    }

    /** Housekeeping only — an expired row is already unusable by {@link #consume}. */
    public int deleteExpiredBefore(Instant cutoff) {
        return jdbc.sql("delete from claude_usage_cli_authorization where expires_at < ?")
                .param(OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .update();
    }
}
