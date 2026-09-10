package com.coc.docs.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * The password sign-in path (ADR-014), alongside the unchanged Keycloak
 * token path ({@link LoginRequest}). Narrow by the same convention as
 * {@code LoginRequest} — nothing here but "here is an email and a password".
 */
public record PasswordLoginRequest(
        @NotBlank @Email String email,
        @NotBlank
                @Schema(description = "Plaintext in transit only — checked against the stored bcrypt hash server-side.")
                String password) {}
