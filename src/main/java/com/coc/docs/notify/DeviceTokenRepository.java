package com.coc.docs.notify;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code device_token} rows (ADR-025).
 *
 * <p>// req: ADR-025
 */
@Repository
public class DeviceTokenRepository {

    private final JdbcClient jdbc;

    public DeviceTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static DeviceToken map(ResultSet rs, int rowNum) throws SQLException {
        return new DeviceToken(
                rs.getString("token"),
                rs.getObject("account_id", UUID.class),
                DevicePlatform.parse(rs.getString("platform")).orElse(DevicePlatform.WEB),
                rs.getString("user_agent"),
                rs.getObject("issued_at", OffsetDateTime.class).toInstant(),
                rs.getObject("last_seen_at", OffsetDateTime.class).toInstant());
    }

    /**
     * Records a device, or re-records one already known.
     *
     * <p>Upsert rather than insert, because the client re-registers on the
     * SDK's own schedule and a duplicate must not be an error. The conflict
     * updates {@code account_id} too: FCM reuses a token across sign-ins on
     * the same browser profile, so a shared machine would otherwise keep
     * delivering the previous person's notifications to the next one.
     */
    public void upsert(String token, UUID accountId, DevicePlatform platform, String userAgent) {
        jdbc.sql("""
                        insert into device_token (token, account_id, platform, user_agent)
                        values (?, ?, ?, ?)
                        on conflict (token) do update
                           set account_id   = excluded.account_id,
                               platform     = excluded.platform,
                               user_agent   = excluded.user_agent,
                               last_seen_at = now()
                        """)
                .params(token, accountId, platform.wire(), userAgent)
                .update();
    }

    /** Every destination for the given accounts. Empty in, empty out. */
    public List<DeviceToken> findByAccountIds(Collection<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select * from device_token where account_id = any (?)")
                .param(accountIds.toArray(UUID[]::new))
                .query(DeviceTokenRepository::map)
                .list();
    }

    /** Removes one destination — the person signed out on that device. */
    public int delete(String token) {
        return jdbc.sql("delete from device_token where token = ?").param(token).update();
    }

    /**
     * Removes destinations FCM has reported dead.
     *
     * <p>Not housekeeping that can be skipped: a token FCM has retired stays in
     * the table forever otherwise, and every later send spends work on an
     * address nobody is listening to.
     */
    public int deleteAll(Collection<String> tokens) {
        if (tokens.isEmpty()) {
            return 0;
        }
        return jdbc.sql("delete from device_token where token = any (?)")
                .param(tokens.toArray(String[]::new))
                .update();
    }

    /** Every destination belonging to one account — used when they sign out everywhere. */
    public int deleteByAccountId(UUID accountId) {
        return jdbc.sql("delete from device_token where account_id = ?").param(accountId).update();
    }

    /** Table hygiene, mirroring SessionMaintenance. Not a security control. */
    public int deleteLastSeenBefore(Instant cutoff) {
        return jdbc.sql("delete from device_token where last_seen_at < ?")
                .param(OffsetDateTime.ofInstant(cutoff, java.time.ZoneOffset.UTC))
                .update();
    }
}
