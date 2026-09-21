package com.coc.docs.claudeusage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
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

    private static final String COLUMNS =
            "id, email, display_name, team_role, status, budget_override_tokens, invited_by, created_at, updated_at";

    /** Every tracked member, newest first. Includes {@code removed} rows -- the dashboard shows them, badged. */
    public List<ClaudeUsageMember> list() {
        return jdbc.sql("select " + COLUMNS + " from claude_usage_member order by created_at desc")
                .query(this::map)
                .list();
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

    /** Last write wins for the display name, same reasoning the reference tool's rollup gives for a rename. */
    public void touchDisplayName(UUID id, String displayName) {
        jdbc.sql("""
                        update claude_usage_member
                        set display_name = coalesce(nullif(?, ''), display_name), updated_at = now()
                        where id = ?
                        """)
                .params(displayName, id)
                .update();
    }
}
