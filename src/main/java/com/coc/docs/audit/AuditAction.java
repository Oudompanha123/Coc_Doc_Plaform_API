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

    public static final String AUTH_SIGN_IN = "auth.sign_in";
    public static final String AUTH_SIGN_OUT = "auth.sign_out";
    public static final String AUTH_REJECTED = "auth.rejected";

    /** Target types, for the {@code target_type} column. */
    public static final String TARGET_PAGE = "page";

    public static final String TARGET_SECTION = "section";
    public static final String TARGET_ACCOUNT = "account";
    public static final String TARGET_SESSION = "session";
}
