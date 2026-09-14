package com.coc.docs.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Narrow, hand-declared per ADR-011 §2.5's mass-assignment defence — there is
 * no path by which a caller can supply a {@code role} or a {@code status}.
 *
 * <p>Submitting this is step 1 of registration, not all of it (ADR-021): it
 * mints and emails a 6-digit code rather than creating an account directly.
 * The account is created — {@code pending} at role {@code guest}, per
 * ADR-017's approval gate — only once that code is confirmed at {@code
 * POST /api/v1/auth/register/verify}.
 */
public record RegisterRequest(
        @NotBlank @Email @Schema(description = "The applicant's email address.") String email,
        @NotBlank @Schema(description = "Display name shown immediately in the new account.") String displayName,
        @NotBlank
                @Size(min = 8, max = 200, message = "Password must be at least 8 characters.")
                @Schema(description = "Plaintext in transit only — hashed (bcrypt) before it ever reaches the database.")
                String password) {}
