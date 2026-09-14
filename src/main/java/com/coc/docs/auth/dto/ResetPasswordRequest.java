package com.coc.docs.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The token proves which account; nothing else in this request does — there
 * is deliberately no email field, so a caller cannot present a token issued
 * for one address alongside an email naming a different one (ADR-017).
 */
public record ResetPasswordRequest(
        @NotBlank @Schema(description = "The opaque token from the emailed reset link.") String token,
        @NotBlank
                @Size(min = 8, max = 200, message = "Password must be at least 8 characters.")
                @Schema(description = "Plaintext in transit only — hashed (bcrypt) before it ever reaches the database.")
                String newPassword) {}
