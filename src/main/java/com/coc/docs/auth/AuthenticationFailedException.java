package com.coc.docs.auth;

/**
 * Any reason a sign-in attempt is refused, on either sign-in path (ADR-014):
 * a forged/expired/wrong-audience token, a nonce that doesn't match, a wrong
 * password, an email with no provisioned account, or one that's deactivated,
 * still pending review, or was rejected.
 *
 * <p>{@link #reason()} is for the audit log only. Per {@code UC-004} E-3, the
 * HTTP response must be identical regardless of which of these happened — no
 * account-state oracle — so nothing in {@code com.coc.docs.web} may read this
 * field into a response body. // req: FR-AUTH-002, TM-001, TM-009
 */
public class AuthenticationFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String reason;

    public AuthenticationFailedException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
