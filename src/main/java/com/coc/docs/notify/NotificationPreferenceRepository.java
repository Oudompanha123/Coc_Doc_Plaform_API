package com.coc.docs.notify;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code notification_preference} rows.
 *
 * <p>Opt-out by absence (ADR-041): a missing row for a (account, type) pair
 * means enabled, so every method here treats "no row" and "row with {@code
 * enabled = true}" the same way rather than requiring a row to exist for
 * every type up front.
 *
 * <p>// req: ADR-041
 */
@Repository
public class NotificationPreferenceRepository {

    private final JdbcClient jdbc;

    public NotificationPreferenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * This account's stored overrides, keyed by {@link NotificationType#key()}.
     * A type with no entry here is enabled — the caller applies that default,
     * not this method, so a caller that ever needs to tell "no row" apart from
     * "row says enabled" still can.
     */
    public Map<String, Boolean> findOverridesFor(UUID accountId) {
        return jdbc.sql("select type, enabled from notification_preference where account_id = ?")
                .param(accountId)
                .query((rs, rowNum) -> Map.entry(rs.getString("type"), rs.getBoolean("enabled")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Whether this account hears about this type — {@code true} if no row exists. */
    public boolean isEnabled(UUID accountId, NotificationType type) {
        Boolean enabled = jdbc.sql("select enabled from notification_preference where account_id = ? and type = ?")
                .params(accountId, type.key())
                .query(Boolean.class)
                .optional()
                .orElse(null);
        return enabled == null || enabled;
    }

    /**
     * Of {@code accountIds}, which currently hear about {@code type} — every
     * one of them minus the subset that has explicitly opted out. Used by
     * {@link NotificationService#notifyApprovers}, which resolves recipients
     * by role rather than one at a time, so filtering here in one query is
     * the difference between one query and one per approver.
     */
    public Set<UUID> filterEnabled(Set<UUID> accountIds, NotificationType type) {
        if (accountIds.isEmpty()) {
            return accountIds;
        }
        String placeholders = String.join(", ", Collections.nCopies(accountIds.size(), "?"));
        Object[] params = new Object[accountIds.size() + 1];
        params[0] = type.key();
        int i = 1;
        for (UUID id : accountIds) {
            params[i++] = id;
        }
        Set<UUID> optedOut = Set.copyOf(jdbc.sql("select account_id from notification_preference where type = ? "
                        + "and enabled = false and account_id in (" + placeholders + ")")
                .params(params)
                .query(UUID.class)
                .list());
        if (optedOut.isEmpty()) {
            return accountIds;
        }
        return accountIds.stream()
                .filter(id -> !optedOut.contains(id))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Upserts this account's preference for one type. */
    public void setEnabled(UUID accountId, NotificationType type, boolean enabled) {
        jdbc.sql(
                        """
                        insert into notification_preference (account_id, type, enabled, updated_at)
                        values (?, ?, ?, now())
                        on conflict (account_id, type)
                        do update set enabled = excluded.enabled, updated_at = excluded.updated_at
                        """)
                .params(accountId, type.key(), enabled)
                .update();
    }
}
