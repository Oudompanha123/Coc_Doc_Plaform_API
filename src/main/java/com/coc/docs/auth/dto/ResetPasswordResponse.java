package com.coc.docs.auth.dto;

/**
 * Returned by {@code POST /api/v1/auth/reset-password} on success. Resetting
 * never signs the caller in itself — no session cookie is set here, and every
 * session that predated the reset is revoked (ADR-018 decision 4) — the
 * caller still goes to {@code /login} to sign in with the new password.
 */
public record ResetPasswordResponse(String message) {}
