package com.coc.docs.integration;

import java.time.Instant;
import java.util.UUID;

/**
 * One append-only outbound-delivery attempt against an {@link Integration}
 * (ADR-037) — a real event or the synthetic {@link #EVENT_TEST}.
 *
 * <p>// req: FR-INTEG-003, FR-INTEG-005, ADR-037
 */
public record IntegrationDelivery(
        long id,
        UUID integrationId,
        String eventType,
        Integer httpStatus,
        boolean success,
        String error,
        Integer latencyMs,
        Instant attemptedAt) {

    /** The synthetic event the "Test" action fires — never a real subscription trigger. */
    public static final String EVENT_TEST = "integration.test";
}
