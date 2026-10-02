package com.coc.docs.auth.dto;

import com.coc.docs.auth.ValidEmail;
import jakarta.validation.constraints.NotBlank;

/**
 * Re-sends the registration code for an application already in flight
 * (ADR-021). Narrow by the same convention as {@link ForgotPasswordRequest}
 * — an email and nothing else.
 */
public record ResendRegistrationCodeRequest(@NotBlank @ValidEmail String email) {}
