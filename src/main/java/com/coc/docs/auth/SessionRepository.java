package com.coc.docs.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code app_session} rows.
 *
 * <p>// req: FR-AUTH-004, FR-AUTH-007, NFR-SEC-SESSION
 */
@Repository
public class SessionRepository {

    private final JdbcClient jdbc;

    public SessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static AppSession map(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime revoked = rs.getObject("revoked_at", OffsetDateTime.class);
        return new AppSession(
                rs.getBytes("token_hash"),
                rs.getObject("account_id", UUID.class),
                rs.getObject("issued_at", OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                revoked == null ? null : revoked.toInstant());
    }

    public void insert(
            byte[] tokenHash,
            UUID accountId,
            Instant expiresAt,
            String userAgent,
            String ipAddress) {

        jdbc.sql("""
                        insert into app_session
                          (token_hash, account_id, expires_at, user_agent, ip_address)
                        values (?, ?, ?, ?, ?::inet)
                        """)
                .params(
                        tokenHash,
                        accountId,
                        OffsetDateTime.ofInstant(expiresAt, java.time.ZoneOffset.UTC),
                        userAgent,
                        ipAddress)
                .update();
    }

    /**
     * Looks up a session by token hash, whatever its state.
     *
     * <p>Expiry and revocation are checked by the caller rather than filtered
     * here, so that a presented-but-dead session can be distinguished from a
     * session that never existed. The distinction never reaches the client — both
     * are a plain 401 — but it is the difference between "your session ended" and
     * "someone is guessing tokens" in the logs.
     */
    public Optional<AppSession> find(byte[] tokenHash) {
        return jdbc.sql("""
                        select token_hash, account_id, issued_at, expires_at, revoked_at
                        from app_session
                        where token_hash = ?
                        """)
                .param(tokenHash)
                .query(SessionRepository::map)
                .optional();
    }

    /**
     * Ends one session server-side.
     *
     * <p>// req: FR-AUTH-007
     */
    public int revoke(byte[] tokenHash) {
        return jdbc.sql("""
                        update app_session set revoked_at = now()
                        where token_hash = ? and revoked_at is null
                        """)
                .param(tokenHash)
                .update();
    }

    /**
     * Ends every session belonging to an account.
     *
     * <p>Called when an account is deactivated or demoted. Without it, a role
     * change would not take effect until the session expired, which would mean a
     * removed admin keeps admin authority for up to a day — the role is read from
     * the database on every request, but only for as long as the session that
     * points at the account stays alive.
     *
     * <p>// req: FR-AUTH-003, FR-AUTH-005
     */
    public int revokeAllFor(UUID accountId) {
        return jdbc.sql("""
                        update app_session set revoked_at = now()
                        where account_id = ? and revoked_at is null
                        """)
                .param(accountId)
                .update();
    }

    /**
     * Removes sessions that expired more than {@code retention} ago.
     *
     * <p>Expired rows are kept for a while on purpose: "was this session already
     * dead when that request arrived?" is a question incident response asks, and
     * deleting on expiry makes it unanswerable.
     */
    public int deleteExpiredBefore(Instant cutoff) {
        return jdbc.sql("delete from app_session where expires_at < ?")
                .param(OffsetDateTime.ofInstant(cutoff, java.time.ZoneOffset.UTC))
                .update();
    }

    /**
     * When each account last signed in, for the account administration screen.
     *
     * <p>One query covering every account rather than one per row. The accounts
     * list is a single page render, and a lookup per account is the N+1 that
     * only becomes visible once the team is big enough for it to hurt.
     *
     * <p>Read from {@code app_session} rather than the audit log: a session row
     * exists <em>because</em> a sign-in issued it, so it is the fact rather
     * than a record of the fact.
     *
     * <p>This is therefore "last sign-in we still hold a session row for".
     * {@link #deleteExpiredBefore} keeps rows one TTL past expiry, so someone
     * who has not signed in for weeks reports nothing at all rather than a
     * misleading date. Absent rather than zero — the caller renders "Never".
     */
    public Map<UUID, Instant> lastSignInByAccount() {
        Map<UUID, Instant> byAccount = new HashMap<>();
        jdbc.sql("select account_id, max(issued_at) as last_sign_in from app_session group by account_id")
                .query((ResultSet rs, int rowNum) -> {
                    byAccount.put(
                            rs.getObject("account_id", UUID.class),
                            rs.getObject("last_sign_in", OffsetDateTime.class).toInstant());
                    return null;
                })
                .list();
        return byAccount;
    }
}
