package com.coc.docs.auth.dto;

/**
 * Returned by {@code POST /api/v1/auth/change-password} on success. Unlike
 * {@code ResetPasswordResponse}, this never ends the caller's own session —
 * they are already inside it and are meant to keep using it; every other
 * session for the account is what gets revoked.
 */
public record ChangePasswordResponse(String message) {}
