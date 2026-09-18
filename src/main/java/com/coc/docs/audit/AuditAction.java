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

    /** TOTP second factor confirmed on an account (ADR-031). */
    public static final String MFA_ENABLED = "auth.mfa_enabled";

    /** TOTP second factor turned off, after re-proving the current password (ADR-031). */
    public static final String MFA_DISABLED = "auth.mfa_disabled";

    /**
     * A content-activity digest was sent on demand rather than on its schedule.
     *
     * <p>Audited because it is a person causing mail containing page titles and
     * editor names to be sent to a recipient list — the same class of act as
     * changing who holds a role, and the only record that it happened at all.
     * The scheduled firing is not audited: it is the system doing what it was
     * configured to do, and an audit row every morning would bury the manual
     * ones it exists to make visible.
     */
    public static final String NOTIFICATION_DIGEST_SEND = "notification.digest_send";

    /** An admin saved a docs-content field (Hero/Steps/Sections) for a product's sub-item. */
    public static final String DOCS_CONTENT_SAVE = "docs_content.save";

    public static final String NAV_PRODUCT_CREATE = "nav.product_create";
    public static final String NAV_PRODUCT_DELETE = "nav.product_delete";
    public static final String NAV_ITEM_CREATE = "nav.item_create";
    public static final String NAV_ITEM_DELETE = "nav.item_delete";

    /**
     * An admin invited a teammate onto the Claude Usage tracking roster (ADR-035).
     * Not raised when {@code ClaudeUsageService.recordUsage} auto-creates a row for
     * an email that reported before anyone invited it -- nobody here did that, a
     * script on someone's own machine did (same reasoning as {@code
     * hrms_notification} not living in this trail).
     */
    public static final String CLAUDE_USAGE_MEMBER_INVITE = "claude_usage_member.invite";

    public static final String CLAUDE_USAGE_MEMBER_ROLE_CHANGE = "claude_usage_member.role_change";
    public static final String CLAUDE_USAGE_MEMBER_BUDGET_CHANGE = "claude_usage_member.budget_change";
    public static final String CLAUDE_USAGE_MEMBER_REMOVE = "claude_usage_member.remove";

    /** Target types, for the {@code target_type} column. */
    public static final String TARGET_PAGE = "page";

    public static final String TARGET_SECTION = "section";
    public static final String TARGET_ACCOUNT = "account";
    public static final String TARGET_SESSION = "session";

    /** A notification the system sent; carries no target id, there is no row to point at. */
    public static final String TARGET_NOTIFICATION = "notification";

    /**
     * docs_content/nav_product/nav_item are keyed by (product key, slug) or a
     * plain string key, not a uuid primary key — unlike page/account, there is
     * no id to put in {@code target_id} for these, so it stays null and the
     * identifying fields travel in {@code detail} instead (same reasoning as
     * {@link #ACCOUNT_DELETE}: the trail has to name the target without a row
     * left to join to).
     */
    public static final String TARGET_DOCS_CONTENT = "docs_content";

    public static final String TARGET_NAV_PRODUCT = "nav_product";
    public static final String TARGET_NAV_ITEM = "nav_item";

    public static final String TARGET_CLAUDE_USAGE_MEMBER = "claude_usage_member";

    /** A super_admin connected a new outgoing webhook integration (ADR-037). */
    public static final String INTEGRATION_CONNECT = "integration.connect";

    /** Renamed or re-subscribed an existing integration — never its target URL or secret, which have no update action of their own today. */
    public static final String INTEGRATION_UPDATE = "integration.update";

    public static final String INTEGRATION_ROTATE_SECRET = "integration.rotate_secret";

    /**
     * The integration's signing secret was nulled and it stopped receiving
     * deliveries. Never raised for a delete -- there is no delete; see
     * {@code Integration}'s Javadoc.
     */
    public static final String INTEGRATION_DISCONNECT = "integration.disconnect";

    public static final String TARGET_INTEGRATION = "integration";
}
