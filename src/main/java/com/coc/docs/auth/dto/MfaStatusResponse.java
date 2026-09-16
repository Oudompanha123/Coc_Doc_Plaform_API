package com.coc.docs.auth.dto;

/**
 * Returned by {@code GET /api/v1/auth/mfa/status} (ADR-031) — what {@code
 * SecurityTab}'s two-factor toggle reflects on load. Carries nothing else:
 * the secret is never re-exposed once enrollment is confirmed.
 */
public record MfaStatusResponse(boolean enabled) {}
