package com.coc.docs.notify;

import java.time.Instant;
import java.util.UUID;

/**
 * One push destination: an app instance a person has granted notification
 * permission on.
 *
 * <p>// req: ADR-025
 */
public record DeviceToken(
        String token,
        UUID accountId,
        DevicePlatform platform,
        String userAgent,
        Instant issuedAt,
        Instant lastSeenAt) {}
