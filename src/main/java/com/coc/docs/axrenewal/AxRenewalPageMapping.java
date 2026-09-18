package com.coc.docs.axrenewal;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code ax_renewal_page_mapping} — one old→new page correspondence
 * (ADR-037). At least one of {@code oldPath}/{@code newPath} is always
 * present: a page can be new (no old side) or removed (no new side), but
 * never neither.
 */
public record AxRenewalPageMapping(
        UUID id,
        UUID projectId,
        String oldPath,
        String newPath,
        String notes,
        int position,
        Instant updatedAt,
        UUID updatedBy) {}
