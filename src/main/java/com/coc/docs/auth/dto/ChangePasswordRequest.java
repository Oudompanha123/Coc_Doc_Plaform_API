package com.coc.docs.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Self-service password change (revision of ADR-017, current-password check
 * added by ADR-022): the caller already holds a session, so unlike {@link
 * ResetPasswordRequest} there is no token here — the account is the one that
 * made the request, never one named in it.
 *
 * <p>{@code currentPassword} is deliberately not {@code @NotBlank}: it is
 * required and verified for a voluntary change, but the first-login
 * completion of a {@code super_admin} invite has none to give — {@code
 * /login-password} just proved the equivalent moments earlier, on this same
 * session. {@code SessionService.changeOwnPassword} is what actually decides
 * which case applies and enforces the field accordingly; this DTO only
 * shapes the wire format. See that method's javadoc and ADR-022.
 *
 * <p>{@code confirmPassword} is checked here as well as by the frontend form:
 * client-side validation is advisory only (CONST-TECH-07), and this is the
 * kind of mistake a compromised or buggy client could otherwise commit
 * straight to the database.
 */
public record ChangePasswordRequest(
        @Schema(description = "Required and verified for a voluntary change; not required (and ignored) during the forced first-login completion. ADR-022.")
                String currentPassword,
        @NotBlank
                @Size(min = 8, max = 200, message = "Password must be at least 8 characters.")
                @Schema(description = "Plaintext in transit only — hashed (bcrypt) before it ever reaches the database.")
                String newPassword,
        @NotBlank @Schema(description = "Must match newPassword exactly.") String confirmPassword) {}
