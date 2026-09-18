package com.coc.docs.axrenewal;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** A row of {@code ax_renewal_architecture_plan} — the resulting layered design for one project (ADR-037). */
public record AxRenewalArchitecturePlan(
        UUID projectId, String summary, JsonNode diagram, Instant updatedAt, UUID updatedBy) {}
