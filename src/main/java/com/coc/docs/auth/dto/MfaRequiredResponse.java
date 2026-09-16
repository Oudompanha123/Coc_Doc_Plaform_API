package com.coc.docs.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Returned by {@code POST /api/v1/auth/login-password} instead of {@code
 * MeResponse} when the account has confirmed TOTP (ADR-028): the password
 * was correct, but no session has been issued yet. The frontend switches to
 * the OTP step and calls {@code POST /api/v1/auth/mfa/verify}, sending along
 * the {@code coc_mfa_pending} cookie this response also sets.
 *
 * <p>{@code mfaRequired} is carried as an explicit field, not merely implied
 * by this response's shape, so the frontend's discriminated check
 * ({@code MfaRequiredError}) reads one field rather than the absence of
 * others.
 */
public record MfaRequiredResponse(
        boolean mfaRequired,
        @Schema(description = "Seconds until the pending MFA challenge expires.") long expiresInSeconds) {}
