package com.coc.docs.axrenewal;

import java.util.UUID;

/**
 * An account was added to an AX Renewal project (ADR-074).
 *
 * <p>Published by {@link AxRenewalService#addMember} only for a real add — a
 * new member, not a repeat, and not the caller adding themselves — and
 * handled after the transaction commits ({@code AxRenewalMemberNotifier}), so
 * an add that rolls back never tells anybody they were added.
 *
 * <p>Ids only. What the notification says is the notification type's own
 * fixed sentence, and anything more is one authenticated click away.
 *
 * <p>// req: ADR-074
 */
public record AxRenewalMemberAddedEvent(UUID projectId, UUID accountId) {}
