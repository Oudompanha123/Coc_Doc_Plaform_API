package com.coc.docs.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Returned by {@code POST /api/v1/auth/register} and {@code
 * POST /api/v1/auth/register/resend} (ADR-021, revision of ADR-014/017).
 * Neither call creates an account — only {@code POST
 * /api/v1/auth/register/verify} does that, once the emailed code is
 * confirmed.
 */
public record RegisterChallengeResponse(
        String message,
        @Schema(description = "Seconds until the emailed code expires.") long expiresInSeconds) {}
