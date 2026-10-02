package com.coc.docs.auth.dto;

import com.coc.docs.auth.ValidEmail;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Step 3 of registration (ADR-021): the code emailed at step 1/2, presented
 * back with the address it was sent to.
 */
public record VerifyRegistrationCodeRequest(
        @NotBlank @ValidEmail @Schema(description = "The address the code was emailed to.") String email,
        @NotBlank
                @Pattern(regexp = "\\d{6}", message = "The code is 6 digits.")
                @Schema(description = "The 6-digit code emailed to the address above.")
                String code) {}
