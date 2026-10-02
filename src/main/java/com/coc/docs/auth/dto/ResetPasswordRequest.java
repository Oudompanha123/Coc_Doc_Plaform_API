package com.coc.docs.auth.dto;

import com.coc.docs.auth.StrongPassword;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * The token proves which account; nothing else in this request does — there
 * is deliberately no email field, so a caller cannot present a token issued
 * for one address alongside an email naming a different one (ADR-018).
 */
public record ResetPasswordRequest(
        @NotBlank @Schema(description = "The opaque token from the emailed reset link.") String token,
        @NotBlank
                @StrongPassword
                @Schema(description = "Plaintext in transit only — hashed (bcrypt) before it ever reaches the database.")
                String newPassword) {}
