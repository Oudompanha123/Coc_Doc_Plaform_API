package com.coc.docs.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Returned by {@code POST /api/auth/register}. Registration never signs the
 * caller in — no session cookie is set here; the account cannot sign in at
 * all until a super_admin approves it (ADR-014).
 */
public record RegisterResponse(
        @Schema(description = "Always PENDING: a super_admin must approve the registration before it can sign in.")
                String status,
        String message) {}
