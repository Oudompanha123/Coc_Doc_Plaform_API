package com.coc.docs.integration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A connected outgoing-webhook integration (ADR-037).
 *
 * <p>{@code signingSecretIv}/{@code signingSecretCiphertext} are null once
 * {@code status} is {@link #STATUS_DISABLED} — {@link IntegrationService#disconnect}
 * nulls them at the same moment it sets the status, so the credential is
 * cryptographically gone rather than merely flagged. Never exposed outside
 * this package; {@code com.coc.docs.web.ApiDtos} has no field for either.
 *
 * <p>// req: FR-INTEG-001, FR-INTEG-004, ADR-037
 */
public record Integration(
        UUID id,
        String provider,
        String name,
        String targetUrl,
        byte[] signingSecretIv,
        byte[] signingSecretCiphertext,
        short encryptionKeyVersion,
        List<String> events,
        String status,
        Instant lastTestedAt,
        String lastTestStatus,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public static final String PROVIDER_WEBHOOK = "webhook";
    public static final String PROVIDER_SLACK = "slack";

    public static final String STATUS_CONNECTED = "connected";
    public static final String STATUS_DISABLED = "disabled";
    public static final String STATUS_ERROR = "error";

    public boolean isConnected() {
        return STATUS_CONNECTED.equals(status);
    }

    public boolean subscribesTo(String eventType) {
        return events.contains(eventType);
    }
}
