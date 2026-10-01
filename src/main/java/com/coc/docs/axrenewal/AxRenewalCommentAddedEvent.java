package com.coc.docs.axrenewal;

import java.util.UUID;

/**
 * A comment was posted on an AX Renewal project (ADR-080).
 *
 * <p>Published by {@link AxRenewalService#addComment} once the comment row
 * and its audit entry are written, and handled after the transaction commits
 * ({@code AxRenewalCommentNotifier}), so a comment that rolls back tells
 * nobody.
 *
 * <p>Ids only, the same as {@link AxRenewalMemberAddedEvent}: neither the
 * body nor the project's name travels with it.
 *
 * <p>// req: ADR-080
 */
public record AxRenewalCommentAddedEvent(UUID projectId, UUID commentId, UUID authorId) {}
