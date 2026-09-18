package com.coc.docs.axrenewal;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code ax_renewal_comment} — a plain remark on a project, visible
 * to anyone who can read the project (the {@code user} floor), unlike the
 * {@code super_admin}-only shared audit log (ADR-037).
 */
public record AxRenewalComment(UUID id, UUID projectId, UUID authorId, String body, Instant createdAt) {}
