package com.coc.docs.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Confirms a TOTP enrollment (ADR-028): the 6-digit code the authenticator app is showing right now. */
public record MfaSetupVerifyRequest(
        @NotBlank
                @Pattern(regexp = "\\d{6}", message = "The code is 6 digits.")
                @Schema(description = "The current 6-digit code from the authenticator app.")
                String code) {}
