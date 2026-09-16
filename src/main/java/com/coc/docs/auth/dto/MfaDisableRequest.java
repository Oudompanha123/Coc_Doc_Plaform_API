package com.coc.docs.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Turns TOTP off (ADR-028). {@code currentPassword} is required and verified
 * server-side — the same reasoning {@code SessionService.changeOwnPassword}
 * already applies to any security-lowering self-service action (ADR-022): a
 * session alone must not be enough to silently remove the account's own
 * second factor.
 */
public record MfaDisableRequest(
        @NotBlank
                @Schema(description = "Plaintext in transit only — checked against the stored bcrypt hash server-side.")
                String currentPassword) {}
