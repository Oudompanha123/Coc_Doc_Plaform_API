package com.coc.docs.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Narrow, hand-declared per ADR-011 §2.5's mass-assignment defence — there is
 * no path by which a caller can supply a {@code role} or a {@code status}.
 * Every self-registered account is created as {@code user}/{@code pending},
 * never anything else (ADR-014).
 */
public record RegisterRequest(
        @NotBlank @Email @Schema(description = "The applicant's email address.") String email,
        @NotBlank @Schema(description = "Display name shown once the account is approved.") String displayName,
        @NotBlank
                @Size(min = 8, max = 200, message = "Password must be at least 8 characters.")
                @Schema(description = "Plaintext in transit only — hashed (bcrypt) before it ever reaches the database.")
                String password) {}
