package com.coc.docs.auth.dto;

/** Returned by {@code POST /api/v1/auth/mfa/disable} on success (ADR-028). */
public record MfaDisableResponse(String message) {}
