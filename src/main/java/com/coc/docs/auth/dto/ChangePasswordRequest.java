package com.coc.docs.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Self-service password write (revision of ADR-017, current-password check
 * added by ADR-022, made unconditional for an existing hash by ADR-024, given
 * a third caller by ADR-030): the caller already holds a session, so unlike
 * {@link ResetPasswordRequest} there is no token here — the account is the
 * one that made the request, never one named in it.
 *
 * <p>{@code currentPassword} is required for two of this endpoint's three
 * callers, not all of them, which is why it carries no {@code @NotBlank}
 * here — Bean Validation cannot see account state, only the request body.
 * The voluntary "My Profile" change and the forced first-login completion
 * both need a value (for the latter it is the temporary password the
 * account was invited with; {@code /login-password} already proved it once
 * to establish the session, and this is a second proof immediately before
 * it is retired, ADR-024). A Google-only account setting its first password
 * (ADR-030) has none to send. {@code SessionService.changeOwnPassword} is
 * what actually decides which rule applies and verifies the value against
 * the stored hash when one exists; this DTO only shapes the wire format.
 *
 * <p>{@code confirmPassword} is checked here as well as by the frontend form:
 * client-side validation is advisory only (CONST-TECH-07), and this is the
 * kind of mistake a compromised or buggy client could otherwise commit
 * straight to the database.
 */
public record ChangePasswordRequest(
        @Schema(description = "The account's current password — the temporary password for the forced first-login completion, the existing password for a voluntary change, or omitted entirely when the account has no password yet. Verified against the stored hash when one exists. ADR-022, ADR-024, ADR-030.")
                String currentPassword,
        @NotBlank
                @Size(min = 8, max = 200, message = "Password must be at least 8 characters.")
                @Schema(description = "Plaintext in transit only — hashed (bcrypt) before it ever reaches the database.")
                String newPassword,
        @NotBlank @Schema(description = "Must match newPassword exactly.") String confirmPassword) {}
