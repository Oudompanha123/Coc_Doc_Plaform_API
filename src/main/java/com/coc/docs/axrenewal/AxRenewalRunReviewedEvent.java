package com.coc.docs.axrenewal;

import java.util.UUID;

/**
 * An implementation run was approved (and applied) or rejected (ADR-081).
 *
 * <p>Published by {@link AxRenewalService#approveImplementation} once the
 * changes are written and the run is {@code applied}, and by {@link
 * AxRenewalService#rejectImplementation} once it is {@code rejected}. A
 * refused or failed approve never publishes. Handled after the transaction
 * commits ({@code AxRenewalRunReviewedNotifier}), which decides whether
 * anybody is told: the starter, when someone else reviewed it.
 *
 * <p>{@code startedBy} may be null for a run whose starter is unknown; then
 * nobody is told. Ids only, the same as the other AX Renewal events.
 *
 * <p>// req: ADR-081
 */
public record AxRenewalRunReviewedEvent(
        UUID projectId, UUID runId, UUID startedBy, UUID reviewedBy, boolean approved) {}
