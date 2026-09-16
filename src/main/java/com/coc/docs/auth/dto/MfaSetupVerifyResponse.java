package com.coc.docs.auth.dto;

/** Returned by {@code POST /api/v1/auth/mfa/setup/verify} on success (ADR-028). */
public record MfaSetupVerifyResponse(String message) {}
