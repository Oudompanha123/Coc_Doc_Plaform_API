package com.coc.docs.capability;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code account_capability} rows.
 *
 * <p>Deliberately separate from {@code account.AccountRepository}: that class
 * owns {@code account_role} and the rank ladder's invariants. A capability
 * grant has none of those (no last-one-remaining guard, no rank to disturb),
 * and keeping the two repositories apart is what makes that true by
 * construction rather than by convention.
 *
 * <p>// req: ADR-016, ADR-037
 */
@Repository
public class CapabilityRepository {

    private final JdbcClient jdbc;

    public CapabilityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every capability this account holds.
     *
     * <p>Resolved per request, exactly like {@code
     * AccountRepository.findPermissions} — never cached in the session row, so
     * a revoked capability takes effect on the caller's very next request.
     */
    public Set<Capability> findForAccount(UUID accountId) {
        List<String> keys = jdbc.sql("select capability_key from account_capability where account_id = ?")
                .param(accountId)
                .query(String.class)
                .list();

        Set<Capability> capabilities = EnumSet.noneOf(Capability.class);
        for (String key : keys) {
            // Unknown key: ignored rather than fatal, same reasoning as
            // AccountRepository.findPermissions -- a capability this build has
            // no constant for cannot be enforced anyway, and refusing to
            // authenticate over it would turn a forward-compatible deployment
            // into an outage.
            Capability.parse(key).ifPresent(capabilities::add);
        }
        return capabilities;
    }

    /** Grants one capability. Idempotent — granting one already held must not disturb {@code granted_at}. */
    public int grant(UUID accountId, Capability capability, UUID grantedBy) {
        return jdbc.sql("""
                        insert into account_capability (account_id, capability_key, granted_by)
                        values (?, ?, ?)
                        on conflict (account_id, capability_key) do nothing
                        """)
                .params(accountId, capability.key(), grantedBy)
                .update();
    }

    /** Revokes one capability. Revoking one not held is a no-op, not an error. */
    public int revoke(UUID accountId, Capability capability) {
        return jdbc.sql("delete from account_capability where account_id = ? and capability_key = ?")
                .params(accountId, capability.key())
                .update();
    }
}
