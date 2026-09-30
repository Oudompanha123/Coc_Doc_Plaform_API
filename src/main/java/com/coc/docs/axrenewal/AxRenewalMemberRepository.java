package com.coc.docs.axrenewal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads and writes {@code ax_renewal_project_member} rows (ADR-069). */
@Repository
public class AxRenewalMemberRepository {

    private final JdbcClient jdbc;

    public AxRenewalMemberRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static AxRenewalMember map(ResultSet rs, int rowNum) throws SQLException {
        return new AxRenewalMember(
                rs.getObject("project_id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getString("display_name"),
                rs.getObject("added_at", OffsetDateTime.class).toInstant());
    }

    /**
     * The project's members, earliest first. {@code displayName} is joined
     * from {@code account} at read time, the same as a comment's author
     * (ADR-045), so a later name change shows up here too.
     */
    public List<AxRenewalMember> listForProject(UUID projectId) {
        return jdbc.sql("""
                        select m.project_id, m.account_id, a.display_name, m.added_at
                        from ax_renewal_project_member m
                        join account a on a.id = m.account_id
                        where m.project_id = ?
                        order by m.added_at, a.display_name
                        """)
                .param(projectId)
                .query(AxRenewalMemberRepository::map)
                .list();
    }

    public boolean isMember(UUID projectId, UUID accountId) {
        return jdbc.sql("select exists (select 1 from ax_renewal_project_member where project_id = ? and account_id = ?)")
                .params(projectId, accountId)
                .query(Boolean.class)
                .single();
    }

    /** Every project this account is a member of — the picker's "Mine" filter, in one query rather than one per row. */
    public Set<UUID> projectIdsFor(UUID accountId) {
        return new HashSet<>(jdbc.sql("select project_id from ax_renewal_project_member where account_id = ?")
                .param(accountId)
                .query(UUID.class)
                .list());
    }

    /** @return false when the account was already a member — adding twice is not an error, and not a second row. */
    public boolean add(UUID projectId, UUID accountId, UUID addedBy) {
        return jdbc.sql("""
                        insert into ax_renewal_project_member (project_id, account_id, added_by)
                        values (?, ?, ?)
                        on conflict do nothing
                        """)
                .params(projectId, accountId, addedBy)
                .update() > 0;
    }

    /** @return false when the account was not a member to begin with. */
    public boolean remove(UUID projectId, UUID accountId) {
        return jdbc.sql("delete from ax_renewal_project_member where project_id = ? and account_id = ?")
                .params(projectId, accountId)
                .update() > 0;
    }

    public int count(UUID projectId) {
        return jdbc.sql("select count(*) from ax_renewal_project_member where project_id = ?")
                .param(projectId)
                .query(Integer.class)
                .single();
    }
}
