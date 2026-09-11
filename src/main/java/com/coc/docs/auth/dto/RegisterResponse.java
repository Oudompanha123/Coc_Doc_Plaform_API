package com.coc.docs.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Returned by {@code POST /api/auth/register}. Registration never signs the
 * caller in itself — no session cookie is set here — but the account is
 * created {@code active} and can sign in on the very next request, via
 * {@code POST /api/v1/auth/login-password} (no {@code super_admin} approval
 * gate; see {@code AuthController.register}'s javadoc, ADR-014).
 */
public record RegisterResponse(
        @Schema(description = "Always ACTIVE: the account can sign in immediately, no approval step.")
                String status,
        String message) {}
