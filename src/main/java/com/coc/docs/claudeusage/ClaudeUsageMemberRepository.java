package com.coc.docs.claudeusage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads and writes {@code claude_usage_member} rows (V22, ADR-035). */
@Repository
public class ClaudeUsageMemberRepository {

    private final JdbcClient jdbc;

    public ClaudeUsageMemberRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private ClaudeUsageMember map(ResultSet rs, int rowNum) throws SQLException {
        Object budget = rs.getObject("budget_override_tokens");
        return new ClaudeUsageMember(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("team_role"),
                rs.getString("status"),
                budget == null ? null : ((Number) budget).longValue(),
                rs.getObject("invited_by", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    /**
     * The name to show for a member: the platform account's own display name
     * when one matches by email, so a rename on Settings → Account shows here
     * too, else the name this table stored. {@code display_name} on this table
     * is only what a reporter last sent (the CLI's saved login name, rewritten
     * on every upload by {@link #touchDisplayName}), so it is the fallback for
     * an email with no account, never the source of truth for one that has
     * one. Matched by email like {@link #avatarUrlsByMemberId}; a scalar
     * subquery, so a member never appears twice. Spelled with the table name,
     * not an alias, so the same expression works in {@code returning}.
     */
    static final String DISPLAY_NAME = """
            coalesce(
              (select nullif(btrim(a.display_name), '') from account a
                where lower(a.email) = lower(claude_usage_member.email) limit 1),
              claude_usage_member.display_name) as display_name""";

    private static final String COLUMNS =
            "id, email, " + DISPLAY_NAME + ", team_role, status, budget_override_tokens, invited_by, created_at, updated_at";

    /** Every tracked member, newest invite first. Includes {@code removed} rows -- the dashboard shows them, badged. */
    public List<ClaudeUsageMember> list() {
        return jdbc.sql("select " + COLUMNS + " from claude_usage_member order by created_at desc")
                .query(this::map)
                .list();
    }

    /**
     * Profile picture per member, keyed by member id, for members whose email
     * matches a platform {@code account} that has one. A tracked person need
     * not have an account at all (see {@link ClaudeUsageMember}), so the match
     * is by email rather than a foreign key, and a member with no match is
     * simply absent from the map -- the frontend falls back to initials.
     */
    public Map<UUID, String> avatarUrlsByMemberId() {
        Map<UUID, String> urls = new HashMap<>();
        jdbc.sql("""
                        select m.id, a.avatar_url
                        from claude_usage_member m
                        join account a on lower(a.email) = lower(m.email)
                        where a.avatar_url is not null
                        """)
                .query(rs -> {
                    urls.put(rs.getObject("id", UUID.class), rs.getString("avatar_url"));
                });
        return urls;
    }

    public Optional<ClaudeUsageMember> findById(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from claude_usage_member where id = ?")
                .param(id)
                .query(this::map)
                .optional();
    }

    /** {@code email} must already be lower-cased and trimmed by the caller. */
    public Optional<ClaudeUsageMember> findByEmail(String email) {
        return jdbc.sql("select " + COLUMNS + " from claude_usage_member where email = ?")
                .param(email)
                .query(this::map)
                .optional();
    }

    public ClaudeUsageMember insert(String email, String displayName, String teamRole, String status, UUID invitedBy) {
        // The text block's trailing whitespace on its last line is stripped by
        // the JLS before concatenation, so "returning " + COLUMNS with a
        // trailing space in the block silently collapsed into "returningid" --
        // the newline below is what actually survives.
        return jdbc.sql("""
                        insert into claude_usage_member (email, display_name, team_role, status, invited_by)
                        values (?, ?, ?, ?, ?)
                        returning
                        """ + COLUMNS)
                .params(email, displayName, teamRole, status, invitedBy)
                .query(this::map)
                .single();
    }

    public void updateTeamRole(UUID id, String teamRole) {
        jdbc.sql("update claude_usage_member set team_role = ?, updated_at = now() where id = ?")
                .params(teamRole, id)
                .update();
    }

    public void updateBudgetOverride(UUID id, Long budgetOverrideTokens) {
        jdbc.sql("update claude_usage_member set budget_override_tokens = ?, updated_at = now() where id = ?")
                .params(budgetOverrideTokens, id)
                .update();
    }

    public void updateStatus(UUID id, String status) {
        jdbc.sql("update claude_usage_member set status = ?, updated_at = now() where id = ?")
                .params(status, id)
                .update();
    }

    /**
     * Last write wins for the stored name, same reasoning the reference tool's
     * rollup gives for a rename. Only shown for an email with no platform
     * account -- one that has an account shows that account's name
     * ({@link #DISPLAY_NAME}).
     */
    public void touchDisplayName(UUID id, String displayName) {
        jdbc.sql("""
                        update claude_usage_member
                        set display_name = coalesce(nullif(?, ''), display_name), updated_at = now()
                        where id = ?
                        """)
                .params(displayName, id)
                .update();
    }

    /**
     * Records that this member's reporter just finished a scan (V65, ADR-097),
     * whether or not it uploaded usage. Server time; never moves backwards.
     * Leaves {@code updated_at} alone -- that is the roster row's edit time.
     */
    public void touchLastScan(UUID id) {
        jdbc.sql("update claude_usage_member set last_scan_at = greatest(last_scan_at, now()) where id = ?")
                .param(id)
                .update();
    }

    /** {@code last_scan_at} per member id, for members that have one. */
    public Map<UUID, Instant> lastScanAtByMemberId() {
        Map<UUID, Instant> scans = new HashMap<>();
        jdbc.sql("select id, last_scan_at from claude_usage_member where last_scan_at is not null")
                .query(rs -> {
                    scans.put(rs.getObject("id", UUID.class), rs.getObject("last_scan_at", OffsetDateTime.class).toInstant());
                });
        return scans;
    }

    /** This member's {@code last_scan_at}, or empty when none is recorded yet. */
    public Optional<Instant> findLastScanAt(UUID id) {
        return jdbc.sql("select last_scan_at from claude_usage_member where id = ? and last_scan_at is not null")
                .param(id)
                .query((rs, rowNum) -> rs.getObject("last_scan_at", OffsetDateTime.class).toInstant())
                .optional();
    }
}
