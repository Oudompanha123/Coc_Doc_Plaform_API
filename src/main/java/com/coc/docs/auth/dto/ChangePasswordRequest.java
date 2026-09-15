package com.coc.docs.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Self-service password change (revision of ADR-017, current-password check
 * added by ADR-022, made unconditional by ADR-024): the caller already holds
 * a session, so unlike {@link ResetPasswordRequest} there is no token here —
 * the account is the one that made the request, never one named in it.
 *
 * <p>{@code currentPassword} is required for every caller of this endpoint —
 * the voluntary "My Profile" change and the forced first-login completion
 * alike. For the latter it is the temporary password the account was
 * invited with; {@code /login-password} already proved it once to establish
 * the session, and this is a second proof immediately before it is retired
 * (ADR-024, revision of ADR-022 — that ADR originally exempted this case).
 * {@code SessionService.changeOwnPassword} is what actually verifies it
 * against the stored hash; this DTO only shapes the wire format.
 *
 * <p>{@code confirmPassword} is checked here as well as by the frontend form:
 * client-side validation is advisory only (CONST-TECH-07), and this is the
 * kind of mistake a compromised or buggy client could otherwise commit
 * straight to the database.
 */
public record ChangePasswordRequest(
        @NotBlank
                @Schema(description = "The account's current password — the temporary password for the forced first-login completion, the existing password for a voluntary change. Verified against the stored hash. ADR-022, ADR-024.")
                String currentPassword,
        @NotBlank
                @Size(min = 8, max = 200, message = "Password must be at least 8 characters.")
                @Schema(description = "Plaintext in transit only — hashed (bcrypt) before it ever reaches the database.")
                String newPassword,
        @NotBlank @Schema(description = "Must match newPassword exactly.") String confirmPassword) {}
