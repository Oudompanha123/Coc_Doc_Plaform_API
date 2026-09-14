package com.coc.docs.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
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
     * Ends every other live session belonging to an account, keeping the one
     * whose hash is passed in.
     *
     * <p>Used by a self-service password change: unlike a reset (which nobody
     * could be using at the moment it lands, since it always follows an
     * emailed link opened outside any session), a change made from within an
     * active session must not end the very request that made it. Every other
     * device is still logged in on the credential just replaced, which is
     * exactly the exposure {@link #revokeAllFor} closes when there is no
     * session left to protect.
     */
    public int revokeAllForExcept(UUID accountId, byte[] exceptTokenHash) {
        return jdbc.sql("""
                        update app_session set revoked_at = now()
                        where account_id = ? and revoked_at is null and token_hash <> ?
                        """)
                .params(accountId, exceptTokenHash)
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

    /** The most recent sign-in for one account (max issued_at across all its sessions). */
    public Optional<Instant> lastSignInForAccount(UUID accountId) {
        return jdbc.sql(
                        "select max(issued_at) as last_sign_in from app_session where account_id = :id")
                .param("id", accountId)
                .query((rs, rowNum) -> {
                    OffsetDateTime ts = rs.getObject("last_sign_in", OffsetDateTime.class);
                    return ts != null ? ts.toInstant() : null;
                })
                .optional();
    }

    /** One of the caller's own sessions, as the Security screen shows it. */
    public record SessionSummary(
            UUID id, String userAgent, String ipAddress, Instant issuedAt, Instant expiresAt, byte[] tokenHash) {}

    /**
     * The caller's own live sessions, newest first.
     *
     * <p>Revoked and expired rows are excluded. They are kept in the table for
     * incident response, but this list answers "where am I signed in right
     * now", and a dead session is not a place you are signed in.
     *
     * <p>Carries {@code tokenHash} so the caller can mark which row is the
     * request making the call. That value never leaves the server — the API
     * sends {@link SessionSummary#id} instead (V6), because the hash is what
     * the server compares a presented token against.
     *
     * <p>// req: FR-AUTH-004, FR-AUTH-007
     */
    public List<SessionSummary> findLiveForAccount(UUID accountId, Instant now) {
        return jdbc.sql("""
                        select id, user_agent, host(ip_address) as ip_address, issued_at, expires_at, token_hash
                        from app_session
                        where account_id = ?
                          and revoked_at is null
                          and expires_at > ?
                        order by issued_at desc
                        """)
                .params(accountId, OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC))
                .query((ResultSet rs, int rowNum) -> new SessionSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("user_agent"),
                        rs.getString("ip_address"),
                        rs.getObject("issued_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        rs.getBytes("token_hash")))
                .list();
    }

    /**
     * Ends one of {@code accountId}'s own sessions.
     *
     * <p>The account id is part of the statement, not checked beforehand.
     * Fetch-then-check leaves a window and, more importantly, leaves the
     * ownership rule somewhere a future caller can forget: written this way,
     * ending someone else's session cannot be expressed. Returns 0 when the id
     * is unknown or belongs to another account, and the caller cannot tell
     * those apart — which is the point.
     *
     * <p>// req: FR-AUTH-007, TM-003
     */
    public int revokeOwnedById(UUID sessionId, UUID accountId) {
        return jdbc.sql("""
                        update app_session set revoked_at = now()
                        where id = ? and account_id = ? and revoked_at is null
                        """)
                .params(sessionId, accountId)
                .update();
    }
}
