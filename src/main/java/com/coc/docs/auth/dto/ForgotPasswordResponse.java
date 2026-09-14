package com.coc.docs.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Returned by {@code POST /api/v1/auth/forgot-password} on {@code 200} —
 * the same message whether the address holds a password and was really
 * emailed, or exists but is Google-only and got nothing (ADR-018 decision 3).
 *
 * <p>An address with no account at all no longer reaches this record — as of
 * ADR-019, that case is a {@code 404} instead, with its own exact message.
 */
public record ForgotPasswordResponse(
        @Schema(description = "The same text whether the address has a password credential or is Google-only; an address with no account at all is a 404 instead (ADR-019).")
                String message) {

    public static ForgotPasswordResponse generic() {
        return new ForgotPasswordResponse(
                "If that email is registered, we've sent password reset instructions.");
    }
}
