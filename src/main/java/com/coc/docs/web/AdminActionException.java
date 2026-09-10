package com.coc.docs.web;

import org.springframework.http.HttpStatus;

/**
 * A clean, client-facing failure for a super_admin account-review action
 * (ADR-014) — not found, or not currently pending review. Unlike
 * {@code AuthenticationFailedException}, the message here is safe to return
 * as-is: these routes are already authenticated as a super_admin, not the
 * public sign-in oracle UC-004 E-3 guards against.
 */
public class AdminActionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient HttpStatus status;

    public AdminActionException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
