package com.coc.docs.axrenewal;

import java.util.UUID;

/**
 * An implementation run reached {@code awaiting_review} (ADR-079).
 *
 * <p>Published only once the run is actually there: by {@link
 * AxRenewalImplementationRunner} after its compare-and-set from {@code
 * generating} succeeds — never for a run that failed, was cancelled, or was
 * closed while it generated — and by {@link AxRenewalService}'s replay, which
 * creates the run directly in that state.
 *
 * <p>{@code skipAccountId} is the one member not to tell, or null for none:
 * the starter of a replay is looking at its result already, while the starter
 * of a generation has been waiting minutes for it.
 *
 * <p>Ids only, the same as {@link AxRenewalMemberAddedEvent}.
 *
 * <p>// req: ADR-079
 */
public record AxRenewalRunReadyEvent(UUID projectId, UUID runId, UUID skipAccountId) {}
