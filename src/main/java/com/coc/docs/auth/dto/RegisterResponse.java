package com.coc.docs.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Returned by {@code POST /api/v1/auth/register/verify} on success — the
 * step that actually creates the account (ADR-021, revision of
 * ADR-014/017). Registration never signs the caller in itself — no session
 * cookie is set here — and, per ADR-017's approval gate, the account is
 * created {@code pending} at role {@code guest}: it exists, but a {@code
 * super_admin} still has to approve it before {@code
 * POST /api/v1/auth/login-password} will work.
 */
public record RegisterResponse(
        @Schema(description = "PENDING: an administrator has to approve the application before sign-in works (ADR-017).")
                String status,
        String message) {}
