package com.coc.docs.audit;

/**
 * The audit action vocabulary.
 *
 * <p>Constants rather than free-form strings at each call site: an audit trail
 * is only queryable if the same event is always spelled the same way, and
 * {@code page.publish} versus {@code page_published} in two places is the kind
 * of drift that is invisible until someone tries to answer a question with it.
 *
 * <p>// req: FR-OPS-001, ADR-006
 */
public final class AuditAction {

    private AuditAction() {}

    public static final String PAGE_DRAFT_SAVE = "page.draft_save";
    public static final String PAGE_PUBLISH = "page.publish";
    public static final String PAGE_CREATE = "page.create";
    public static final String PAGE_RENAME = "page.rename";
    public static final String PAGE_DELETE = "page.delete";
    public static final String PAGE_REORDER = "page.reorder";

    public static final String SECTION_DB_BACKED = "section.db_backed";

    public static final String ACCOUNT_CREATE = "account.create";
    public static final String ACCOUNT_ROLE_CHANGE = "account.role_change";
    public static final String ACCOUNT_DEACTIVATE = "account.deactivate";
    public static final String ACCOUNT_REACTIVATE = "account.reactivate";

    /**
     * The account row is gone (ADR-023). The only account action whose target
     * cannot be looked up afterwards, so the entry carries the subject email
     * and roles in {@code detail} — the trail has to answer "who was removed"
     * without a row left to join to.
     */
    public static final String ACCOUNT_DELETE = "account.delete";

    public static final String ACCOUNT_REGISTER = "account.register";
    public static final String ACCOUNT_APPROVE = "account.approve";
    public static final String ACCOUNT_REJECT = "account.reject";

    /** A registration code was minted and emailed — the first step of ADR-021, before any account row exists. */
    public static final String ACCOUNT_REGISTER_OTP_REQUEST = "account.register_otp_request";

    public static final String AUTH_SIGN_IN = "auth.sign_in";
    public static final String AUTH_SIGN_OUT = "auth.sign_out";
    public static final String AUTH_REJECTED = "auth.rejected";

    public static final String PASSWORD_RESET_REQUEST = "auth.password_reset_request";
    public static final String PASSWORD_RESET_COMPLETE = "auth.password_reset_complete";

    /**
     * A self-service password write — first-login (temporary password), a
     * voluntary change, or a Google-only account setting its first password
     * (ADR-030). {@code detail.kind} on the entry tells the last one apart
     * from a change.
     */
    public static final String PASSWORD_CHANGE_COMPLETE = "auth.password_change_complete";

    /** TOTP second factor confirmed on an account (ADR-028). */
    public static final String MFA_ENABLED = "auth.mfa_enabled";

    /** TOTP second factor turned off, after re-proving the current password (ADR-028). */
    public static final String MFA_DISABLED = "auth.mfa_disabled";

    /** Target types, for the {@code target_type} column. */
    public static final String TARGET_PAGE = "page";

    public static final String TARGET_SECTION = "section";
    public static final String TARGET_ACCOUNT = "account";
    public static final String TARGET_SESSION = "session";
}
