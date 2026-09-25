package com.coc.docs.claudeusage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads and writes {@code claude_usage_ingest_token} rows (V35, ADR-047). */
@Repository
public class ClaudeUsageIngestTokenRepository {

    private final JdbcClient jdbc;

    public ClaudeUsageIngestTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS = "id, account_id, token_hash, label, created_at, last_used_at, revoked_at";

    private ClaudeUsageIngestToken map(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime lastUsed = rs.getObject("last_used_at", OffsetDateTime.class);
        OffsetDateTime revoked = rs.getObject("revoked_at", OffsetDateTime.class);
        return new ClaudeUsageIngestToken(
                rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getString("token_hash"),
                rs.getString("label"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                lastUsed == null ? null : lastUsed.toInstant(),
                revoked == null ? null : revoked.toInstant());
    }

    public ClaudeUsageIngestToken insert(UUID accountId, String tokenHash, String label) {
        return jdbc.sql("""
                        insert into claude_usage_ingest_token (account_id, token_hash, label)
                        values (?, ?, ?)
                        returning
                        """ + COLUMNS)
                .params(accountId, tokenHash, label)
                .query(this::map)
                .single();
    }

    /** Every non-revoked token for one account, newest first — the Settings "usage tokens" list. */
    public List<ClaudeUsageIngestToken> listActiveByAccount(UUID accountId) {
        return jdbc.sql("select " + COLUMNS
                        + " from claude_usage_ingest_token where account_id = ? and revoked_at is null"
                        + " order by created_at desc")
                .param(accountId)
                .query(this::map)
                .list();
    }

    /**
     * The hot path {@code POST /api/ingest} calls on every request: matches the
     * partial index ({@code where revoked_at is null}) exactly.
     */
    public Optional<ClaudeUsageIngestToken> findActiveByHash(String tokenHash) {
        return jdbc.sql("select " + COLUMNS
                        + " from claude_usage_ingest_token where token_hash = ? and revoked_at is null")
                .param(tokenHash)
                .query(this::map)
                .optional();
    }

    /** Scoped to {@code accountId} so one account can never revoke another's token. */
    public int revoke(UUID id, UUID accountId) {
        return jdbc.sql("""
                        update claude_usage_ingest_token set revoked_at = now()
                        where id = ? and account_id = ? and revoked_at is null
                        """)
                .params(id, accountId)
                .update();
    }

    /** Best-effort activity marker for the Settings list — never a security control. */
    public void touchLastUsed(UUID id) {
        jdbc.sql("update claude_usage_ingest_token set last_used_at = now() where id = ?")
                .param(id)
                .update();
    }
}
