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

    /**
     * Linked, re-linked or unlinked an account to an HRMS attendance name
     * (V47). Audited because it decides whose attendance record that account's
     * holder is shown — a wrong value discloses one employee's times to
     * another, which is a security change rather than a cosmetic one.
     */
    public static final String ACCOUNT_HRMS_LINK = "account.hrms_link";
    public static final String ACCOUNT_DEACTIVATE = "account.deactivate";
    public static final String ACCOUNT_REACTIVATE = "account.reactivate";

    /**
     * A self-service deactivation was scheduled but has not taken effect yet
     * (revision of ADR-042) — {@code detail} carries {@code days} and {@code
     * scheduledFor}. Distinct from {@link #ACCOUNT_DEACTIVATE}, which is only
     * written once {@code deactivated_at} is actually set, whether that is
     * immediately (admin path) or once the grace period elapses (self-service
     * sweep).
     */
    public static final String ACCOUNT_DEACTIVATE_SCHEDULE = "account.deactivate_schedule";

    /**
     * A pending self-service deactivation was cancelled by its owner signing
     * back in before it took effect (revision of ADR-042).
     */
    public static final String ACCOUNT_DEACTIVATE_CANCEL = "account.deactivate_cancel";

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

    /**
     * A capability (e.g. {@code developer}) was granted or revoked on an
     * account — a different, rank-independent axis from {@link
     * #ACCOUNT_ROLE_CHANGE} (ADR-037).
     */
    public static final String ACCOUNT_CAPABILITY_GRANT = "account.capability_grant";

    public static final String ACCOUNT_CAPABILITY_REVOKE = "account.capability_revoke";

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

    /** An account minted or revoked its own {@code /api/ingest} bearer token (V35, ADR-047). */
    public static final String CLAUDE_USAGE_INGEST_TOKEN_CREATE = "claude_usage_ingest_token.create";

    public static final String CLAUDE_USAGE_INGEST_TOKEN_REVOKE = "claude_usage_ingest_token.revoke";

    /** AX Renewal actions (ADR-037) — a legacy-migration planning workspace, its plan and its implementation runs. */
    public static final String AX_RENEWAL_PROJECT_CREATE = "ax_renewal.project_create";

    /**
     * The project row is gone — a hard delete, unlike {@link
     * #AX_RENEWAL_COMMENT_DELETE} (ADR-051). {@code target_id} keeps pointing
     * at a row that no longer exists, the same as {@link #ACCOUNT_DELETE}, so
     * {@code detail} carries the project's name since nothing is left to join
     * to for it.
     */
    public static final String AX_RENEWAL_PROJECT_DELETE = "ax_renewal.project_delete";

    public static final String AX_RENEWAL_MANIFEST_ATTACH = "ax_renewal.manifest_attach";
    public static final String AX_RENEWAL_ANALYSIS_RUN = "ax_renewal.analysis_run";
    public static final String AX_RENEWAL_STACK_MOVE_EDIT = "ax_renewal.stack_move_edit";
    public static final String AX_RENEWAL_ARCHITECTURE_EDIT = "ax_renewal.architecture_edit";
    public static final String AX_RENEWAL_PAGES_EDIT = "ax_renewal.pages_edit";
    public static final String AX_RENEWAL_DATABASE_PLAN_EDIT = "ax_renewal.database_plan_edit";
    public static final String AX_RENEWAL_DATABASE_CONNECT = "ax_renewal.database_connect";
    public static final String AX_RENEWAL_DESTINATION_SET = "ax_renewal.destination_set";
    public static final String AX_RENEWAL_SECTION_CONFIRM = "ax_renewal.section_confirm";
    public static final String AX_RENEWAL_IMPLEMENTATION_START = "ax_renewal.implementation_start";
    public static final String AX_RENEWAL_IMPLEMENTATION_APPROVE = "ax_renewal.implementation_approve";
    public static final String AX_RENEWAL_IMPLEMENTATION_REJECT = "ax_renewal.implementation_reject";

    /** A running Generate attempt stopped deliberately, before it ever reached awaiting_review (ADR-049). */
    public static final String AX_RENEWAL_IMPLEMENTATION_CANCEL = "ax_renewal.implementation_cancel";

    /**
     * Rebuild reused a prior applied run's exact generated changes instead
     * of asking the model again, because the confirmed plan had not changed
     * since (ADR-056). {@code detail} carries {@code replayedFromRunId} —
     * distinct from {@link #AX_RENEWAL_IMPLEMENTATION_START}, since nothing
     * here actually ran the model.
     */
    public static final String AX_RENEWAL_IMPLEMENTATION_REPLAY = "ax_renewal.implementation_replay";

    public static final String AX_RENEWAL_COMMENT_ADD = "ax_renewal.comment_add";

    /**
     * A comment hidden from a project's thread (ADR-045). Soft: the row
     * stays, so this entry and the {@code comment_add} before it still refer
     * to something that exists. {@code detail} carries {@code commentId} and
     * {@code ownComment} — whether somebody removed their own remark or a
     * {@code super_admin} removed another person's, which is the part a
     * reader of this log will actually want to know.
     */
    public static final String AX_RENEWAL_COMMENT_DELETE = "ax_renewal.comment_delete";

    /** Starting/stopping the Run Locally dev server (ADR-048) — the process itself is never persisted, so this entry is the only durable record that it ran at all. */
    public static final String AX_RENEWAL_RUN_LOCALLY_START = "ax_renewal.run_locally_start";

    public static final String AX_RENEWAL_RUN_LOCALLY_STOP = "ax_renewal.run_locally_stop";

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

    public static final String TARGET_CLAUDE_USAGE_INGEST_TOKEN = "claude_usage_ingest_token";

    public static final String TARGET_AX_RENEWAL_PROJECT = "ax_renewal_project";
}
