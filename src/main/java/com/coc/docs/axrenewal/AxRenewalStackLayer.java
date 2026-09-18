package com.coc.docs.axrenewal;

import java.time.Instant;
import java.util.UUID;

/** A row of {@code ax_renewal_stack_layer} — one architectural layer's old→new target (ADR-037). */
public record AxRenewalStackLayer(
        UUID id,
        UUID projectId,
        String layerKey,
        String oldTarget,
        String newTarget,
        int position,
        Instant updatedAt,
        UUID updatedBy) {}
