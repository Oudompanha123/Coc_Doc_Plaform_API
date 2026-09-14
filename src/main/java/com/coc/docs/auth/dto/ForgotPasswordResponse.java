package com.coc.docs.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Returned by {@code POST /api/v1/auth/forgot-password} — always the same
 * message, whatever actually happened server-side (ADR-017 decision 3, the
 * {@code UC-004} E-3 anti-enumeration principle applied to this endpoint).
 */
public record ForgotPasswordResponse(
        @Schema(description = "The same text whether or not the address has an account with a password.")
                String message) {

    public static ForgotPasswordResponse generic() {
        return new ForgotPasswordResponse(
                "If that email is registered, we've sent password reset instructions.");
    }
}
