package com.coc.docs.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Narrow, hand-declared per ADR-011 §2.5's mass-assignment defence — this DTO
 * carries exactly one field, on purpose. There is no path by which a caller
 * of this endpoint can supply a {@code role}, an account id, or anything else
 * that isn't "here is a Google ID token".
 */
public record LoginRequest(
        @NotBlank
                @Schema(
                        description = "The Google ID token (JWT credential) returned by Google Identity Services.",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String idToken) {}
