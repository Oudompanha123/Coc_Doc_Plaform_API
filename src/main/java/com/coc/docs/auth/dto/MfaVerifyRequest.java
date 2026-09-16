package com.coc.docs.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Redeems the pending-MFA cookie a successful {@code /login-password} call
 * left behind (ADR-031): the 6-digit code from the account's authenticator
 * app — or the configured developer code, when {@code app.otp.enabled} is
 * false.
 */
public record MfaVerifyRequest(
        @NotBlank
                @Pattern(regexp = "\\d{6}", message = "The code is 6 digits.")
                @Schema(description = "The current 6-digit code from the authenticator app (or the configured dev code in test mode).")
                String code) {}
