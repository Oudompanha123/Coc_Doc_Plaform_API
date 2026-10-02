package com.coc.docs.auth.dto;

import com.coc.docs.auth.ValidEmail;
import jakarta.validation.constraints.NotBlank;

/**
 * Narrow by the same convention as {@link PasswordLoginRequest} — an email
 * and nothing else. There is no {@code password} field here: this request
 * only starts the reset, it does not perform one (ADR-018).
 */
public record ForgotPasswordRequest(@NotBlank @ValidEmail String email) {}
