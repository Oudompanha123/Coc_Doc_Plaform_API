package com.coc.docs.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code account_totp} rows (ADR-028).
 *
 * <p>// req: FR-AUTH-009, ADR-028
 */
@Repository
public class AccountTotpRepository {

    private final JdbcClient jdbc;

    public AccountTotpRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static AccountTotp map(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime confirmedAt = rs.getObject("confirmed_at", OffsetDateTime.class);
        long lastUsedStep = rs.getLong("last_used_step");
        boolean lastUsedStepNull = rs.wasNull();
        return new AccountTotp(
                rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getString("secret"),
                confirmedAt == null ? null : confirmedAt.toInstant(),
                lastUsedStepNull ? null : lastUsedStep,
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    public Optional<AccountTotp> find(UUID accountId) {
        return jdbc.sql("""
                        select id, account_id, secret, confirmed_at, last_used_step, created_at, updated_at
                        from account_totp
                        where account_id = ?
                        """)
                .param(accountId)
                .query(AccountTotpRepository::map)
                .optional();
    }

    /**
     * Starts (or restarts) an enrollment: replaces whatever row exists for
     * this account — pending or not — with a fresh, unconfirmed secret.
     *
     * <p>{@code TotpService.startSetup} refuses outright when a confirmed
     * row already exists, so in practice this only ever replaces a pending,
     * never-confirmed attempt.
     *
     * <p><b>A single atomic {@code INSERT ... ON CONFLICT}, not
     * delete-then-insert.</b> The latter has a real race window between the
     * two statements: two concurrent calls for the same account (React
     * Strict Mode double-invokes an effect exactly like this in
     * development, and nothing stops a genuine double-click either) can
     * each see the row already gone and then both try to insert, tripping
     * {@code account_totp_account_uq} with an unhandled {@code
     * DuplicateKeyException} — which is exactly what happened before this
     * was written this way. The upsert makes "replace this account's row"
     * one statement Postgres resolves atomically, so a second concurrent
     * call updates in place instead of colliding.
     */
    public AccountTotp upsertPending(UUID accountId, String secret) {
        jdbc.sql("""
                        insert into account_totp (account_id, secret)
                        values (?, ?)
                        on conflict (account_id) do update
                            set secret = excluded.secret,
                                confirmed_at = null,
                                last_used_step = null,
                                updated_at = now()
                        """)
                .params(accountId, secret)
                .update();

        return find(accountId)
                .orElseThrow(() -> new IllegalStateException(
                        "account_totp row for account " + accountId + " vanished immediately after upsert"));
    }

    /** Confirms a pending enrollment — from here on it gates sign-in. */
    public int confirm(UUID accountId) {
        return jdbc.sql("update account_totp set confirmed_at = now(), updated_at = now() where account_id = ?")
                .param(accountId)
                .update();
    }

    /** Records the step a real code was just accepted for, closing the replay window. */
    public int updateLastUsedStep(UUID accountId, long step) {
        return jdbc.sql("update account_totp set last_used_step = ?, updated_at = now() where account_id = ?")
                .params(step, accountId)
                .update();
    }

    /** Turns TOTP off entirely — re-enrolling afterwards starts from a fresh secret. */
    public int delete(UUID accountId) {
        return jdbc.sql("delete from account_totp where account_id = ?").param(accountId).update();
    }
}
