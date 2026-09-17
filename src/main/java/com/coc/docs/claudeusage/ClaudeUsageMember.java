package com.coc.docs.claudeusage;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code claude_usage_member} (V22, ADR-035) — the opt-in Claude Code
 * usage tracking roster. Not an {@code account}: a tracked person need not be
 * able to sign in to this platform at all.
 */
public record ClaudeUsageMember(
        UUID id,
        String email,
        String displayName,
        String teamRole,
        String status,
        Long budgetOverrideTokens,
        UUID invitedBy,
        Instant createdAt,
        Instant updatedAt) {

    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_MEMBER = "member";

    public static final String STATUS_INVITED = "invited";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_REMOVED = "removed";
}
