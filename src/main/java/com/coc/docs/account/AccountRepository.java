package com.coc.docs.account;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code account} rows and the roles they hold.
 *
 * <p>Roles live in {@code account_role} (ADR-016), so every read here joins and
 * aggregates them rather than selecting a column. They are aggregated in the
 * same statement rather than fetched per account: the account list is one
 * query, not one plus N.
 *
 * <p>// req: FR-AUTH-003, FR-AUTH-005, FR-AUTH-008, ADR-014, ADR-016
 */
@Repository
public class AccountRepository {

    /**
     * The account columns, always aliased {@code a}.
     *
     * <p>{@code password_hash} itself is deliberately absent: every query in
     * this class except {@link #findCredentialByEmailIgnoreCase} and
     * {@link #findCredentialById} must be incapable of reading it. Kept as its
     * own list rather than appended, so neither can drift into the other by
     * accident. {@code has_password} (ADR-030) is not the exception to that —
     * it is a boolean Postgres computes from the column, never the column
     * itself, so it is safe to include everywhere.
     */
    private static final String ACCOUNT_COLUMNS =
            "a.id, a.email, a.display_name, a.avatar_url, a.avatar_uploaded_at, a.status, a.deactivated_at, "
                    + "a.deactivation_scheduled_for, "
                    + "a.created_at, a.updated_at, a.must_change_password, (a.password_hash is not null) as has_password";

    /**
     * {@code filter (where ...)} rather than a plain {@code array_agg}: the
     * left join produces one null row for an account holding no roles, and
     * without the filter that account would come back holding a set containing
     * null instead of an empty one.
     */
    private static final String ROLES_COLUMN =
            "coalesce(array_agg(ar.role_key::text) filter (where ar.role_key is not null), '{}') as roles";

    private static final String FROM =
            " from account a left join account_role ar on ar.account_id = a.id ";

    /** Grouped by the primary key, so every other account column is functionally dependent and needs no repeating. */
    private static final String GROUP_BY = " group by a.id ";

    private static final String SELECT = "select " + ACCOUNT_COLUMNS + ", " + ROLES_COLUMN + FROM;

    private static final String SELECT_WITH_CREDENTIAL =
            "select " + ACCOUNT_COLUMNS + ", a.password_hash, " + ROLES_COLUMN + FROM;

    private static final RowMapper<Account> MAPPER = AccountRepository::map;

    private static final RowMapper<AccountCredential> CREDENTIAL_MAPPER =
            (rs, rowNum) -> new AccountCredential(map(rs, rowNum), rs.getString("password_hash"));

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;

    public AccountRepository(JdbcClient jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    private static Account map(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime deactivated = rs.getObject("deactivated_at", OffsetDateTime.class);
        OffsetDateTime scheduledFor = rs.getObject("deactivation_scheduled_for", OffsetDateTime.class);
        OffsetDateTime avatarUploadedAt = rs.getObject("avatar_uploaded_at", OffsetDateTime.class);
        return new Account(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("display_name"),
                readRoles(rs),
                rs.getString("avatar_url"),
                avatarUploadedAt == null ? null : avatarUploadedAt.toInstant(),
                AccountStatus.fromDb(rs.getString("status")),
                deactivated == null ? null : deactivated.toInstant(),
                scheduledFor == null ? null : scheduledFor.toInstant(),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                rs.getBoolean("must_change_password"),
                rs.getBoolean("has_password"));
    }

    /**
     * Fails loudly on a role name this application does not know, rather than
     * dropping it.
     *
     * <p>Silently skipping would quietly reduce someone's access — and a
     * reduction in access reads, to everyone involved, as the system working
     * correctly. Nobody reports it, so it is never found.
     */
    private static Set<AppRole> readRoles(ResultSet rs) throws SQLException {
        Array array = rs.getArray("roles");
        if (array == null) {
            return EnumSet.noneOf(AppRole.class);
        }
        String[] keys = (String[]) array.getArray();
        Set<AppRole> roles = EnumSet.noneOf(AppRole.class);
        for (String key : keys) {
            roles.add(AppRole.parse(key)
                    .orElseThrow(() -> new IllegalStateException(
                            "account_role.role_key holds a value outside the app_role enum: " + key)));
        }
        return roles;
    }

    // -----------------------------------------------------------------------
    // Reads
    // -----------------------------------------------------------------------

    public Optional<Account> findById(UUID id) {
        return jdbc.sql(SELECT + " where a.id = ?" + GROUP_BY)
                .param(id)
                .query(MAPPER)
                .optional();
    }

    /**
     * Case-insensitive, because Google returns the address as the user typed it
     * at some point in the past and {@code Editor@example.com} must not become a
     * second account for the same person.
     */
    public Optional<Account> findByEmail(String email) {
        return jdbc.sql(SELECT + " where lower(a.email) = lower(?)" + GROUP_BY)
                .param(email)
                .query(MAPPER)
                .optional();
    }

    public List<Account> findAll() {
        return jdbc.sql(SELECT + GROUP_BY + " order by a.email")
                .query(MAPPER)
                .list();
    }

    /**
     * Backs the {@code super_admin} approval queue (ADR-014).
     *
     * <p>Ordered oldest first: someone who registered on Monday should not sit
     * behind Friday's registrations.
     */
    public List<Account> findByStatus(AccountStatus status) {
        return jdbc.sql(SELECT + " where a.status = ?::account_status" + GROUP_BY + " order by a.created_at")
                .param(status.wire())
                .query(MAPPER)
                .list();
    }

    /**
     * The password sign-in path only (ADR-014) — the one query in this class
     * that reads {@code password_hash}.
     *
     * <p>Returns the row even when the account cannot sign in. Deciding that is
     * the caller's job, and it must compare the password regardless, or the
     * response time alone tells an attacker which addresses are registered.
     */
    public Optional<AccountCredential> findCredentialByEmailIgnoreCase(String email) {
        return jdbc.sql(SELECT_WITH_CREDENTIAL + " where lower(a.email) = lower(?)" + GROUP_BY)
                .param(email)
                .query(CREDENTIAL_MAPPER)
                .optional();
    }

    /**
     * The self-service password-change path only: the caller already holds a
     * session for this exact id, so there is no enumeration risk here the way
     * there is for {@link #findCredentialByEmailIgnoreCase}.
     */
    public Optional<AccountCredential> findCredentialById(UUID id) {
        return jdbc.sql(SELECT_WITH_CREDENTIAL + " where a.id = ?" + GROUP_BY)
                .param(id)
                .query(CREDENTIAL_MAPPER)
                .optional();
    }

    /**
     * Every permission granted by any role this account holds.
     *
     * <p>Resolved per request alongside the account, never cached in the
     * session row and never carried in a token: a revoked permission has to
     * take effect on the caller's next request, which is the whole reason
     * sessions here are opaque rather than JWTs (ADR-011).
     *
     * <p>// req: FR-AUTH-005, TM-003
     */
    public Set<Permission> findPermissions(UUID accountId) {
        List<String> keys = jdbc.sql("""
                        select distinct rp.permission_key
                        from account_role ar
                        join role_permission rp on rp.role_key = ar.role_key
                        where ar.account_id = ?
                        """)
                .param(accountId)
                .query(String.class)
                .list();

        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        for (String key : keys) {
            // Unknown key: the database grants something this build has no
            // constant for. Ignored rather than fatal — a permission the code
            // never checks cannot be enforced anyway, and refusing to
            // authenticate would turn a forward-compatible deployment into an
            // outage. PermissionTest is what keeps the two in step.
            Permission.parse(key).ifPresent(permissions::add);
        }
        return permissions;
    }

    /**
     * Whether the account table holds no rows at all.
     *
     * <p>Backs the installation-bootstrap check in {@code SessionService}: the
     * very first sign-in an installation ever sees is seated as an active
     * {@code super_admin} rather than queued pending, because a table with
     * zero accounts has nobody who could ever approve one. Narrow on purpose —
     * this is "no accounts exist", not "no super admin exists"; see the caller
     * for why the weaker test is the wrong one.
     */
    public boolean isEmpty() {
        Integer count = jdbc.sql("select count(*) from account").query(Integer.class).single();
        return count == null || count == 0;
    }

    /**
     * Every account holding a role and still able to act.
     *
     * <p>{@code status = 'active'} as well as {@code deactivated_at is null}:
     * a pending applicant who somehow held a role is not someone to notify, and
     * neither is a rejected one.
     *
     * <p>// req: FR-AUTH-008, ADR-026
     */
    public List<Account> findActiveByRole(AppRole role) {
        return jdbc.sql(SELECT
                        + " where a.id in (select ar.account_id from account_role ar"
                        + "                 where ar.role_key = ?::app_role)"
                        + "   and a.deactivated_at is null"
                        + "   and a.status = 'active'::account_status"
                        + GROUP_BY
                        + " order by a.created_at")
                .param(role.wire())
                .query(MAPPER)
                .list();
    }

    /**
     * Whether removing {@code excluding} would leave no active super admin.
     *
     * <p>The database holds the same rule as a deferred constraint trigger, which
     * is the control that actually cannot be bypassed. This exists so the API can
     * answer 409 with an explanation rather than surfacing a raised Postgres
     * exception as a 500.
     *
     * <p>// req: UC-004 E-4, FR-AUTH-008
     */
    public boolean isLastActiveSuperAdmin(UUID excluding) {
        Integer remaining = jdbc.sql("""
                        select count(*)
                        from account_role ar
                        join account a on a.id = ar.account_id
                        where ar.role_key = 'super_admin'
                          and a.deactivated_at is null
                          and a.id <> ?
                        """)
                .param(excluding)
                .query(Integer.class)
                .single();
        return remaining == null || remaining == 0;
    }

    // -----------------------------------------------------------------------
    // Writes
    // -----------------------------------------------------------------------

    /**
     * Creates an account for a verified Google identity.
     *
     * <p>Role defaults to {@code user} and is never taken from the sign-in: a new
     * signer-in gets read access and nothing more, and someone with
     * {@code super_admin} has to grant anything above that. This is what stops
     * account creation from being a privilege-escalation path (FR-AUTH-005).
     *
     * <p>The status is a parameter because this is the door a
     * {@code super_admin} uses: that caller <em>is</em> the approval, and
     * sending their own decision to a queue would ask them to approve it
     * twice. Somebody arriving under their own steam goes through
     * {@link #insertApplication} instead.
     */
    public Account insert(
            String email, String displayName, AppRole role, String avatarUrl, AccountStatus status) {
        return insert(email, displayName, role, avatarUrl, status, null, false);
    }

    /**
     * Creates an account carrying a password credential from the moment it
     * exists — the {@code super_admin} temporary-password invite path (revision
     * of ADR-017). {@code passwordHash} is already a bcrypt digest; hashing
     * belongs to the caller's {@code PasswordEncoder}, not here, for the same
     * reason {@link #insertApplication} never hashes either.
     */
    public Account insert(
            String email,
            String displayName,
            AppRole role,
            String avatarUrl,
            AccountStatus status,
            String passwordHash,
            boolean mustChangePassword) {
        UUID id = jdbc.sql("""
                        insert into account
                          (email, display_name, avatar_url, status, password_hash, must_change_password)
                        values (?, ?, ?, ?::account_status, ?, ?)
                        returning id
                        """)
                .params(email, displayName, avatarUrl, status.wire(), passwordHash, mustChangePassword)
                .query(UUID.class)
                .single();
        grantRole(id, role, null);
        return findById(id).orElseThrow();
    }

    /**
     * Creates an account for somebody asking to be let in (ADR-017).
     *
     * <p>{@code pending} at role {@code guest}: the row exists, holds a role
     * that grants nothing and opens nothing, because {@link Account#canSignIn()}
     * is false while the status is pending. Google has vouched that the address
     * is real; it has not vouched that its owner belongs here, and those are
     * different claims.
     *
     * <p>Status and role are literals, never parameters. Both sign-up doors
     * reach this method, and a request that could choose either would be a
     * self-service route to {@code super_admin} — TM-025, the second-highest
     * threat on this project.
     *
     * <p>{@code passwordHash} is null for a Google applicant and
     * {@code avatarUrl} is null for a password applicant; neither path has the
     * other's value to offer.
     */
    public Account insertApplication(
            String email, String displayName, String avatarUrl, String passwordHash) {
        UUID id = jdbc.sql("""
                        insert into account (email, display_name, avatar_url, password_hash, status)
                        values (?, ?, ?, ?, 'pending'::account_status)
                        returning id
                        """)
                .params(email, displayName, avatarUrl, passwordHash)
                .query(UUID.class)
                .single();
        grantRole(id, AppRole.GUEST, null);
        Account created = findById(id).orElseThrow();

        // Published here rather than from the two callers, because this INSERT
        // is the one statement both registration doors pass through: the
        // password path via AccountService.register once the emailed code is
        // confirmed (ADR-021), the Google path via SessionService on a first
        // sign-in. Publishing from the call sites would leave a third door free
        // to forget, which is a mistake this project has already made once —
        // the approval gate had to be fixed in both doors for that reason.
        //
        // Delivery is deferred to after commit by the listener, so a rolled
        // back registration announces nobody (ADR-026 decision 1).
        events.publishEvent(
                new AccountRegisteredEvent(created.id(), created.email(), created.avatarUrl()));
        return created;
    }

    /**
     * Sets a new password hash — the reset path (ADR-018), the self-service
     * change path (revision of ADR-017), and, since ADR-030, the same
     * self-service path also giving a Google-only account its first password
     * hash. {@code /forgot-password} still does not go there as a side
     * effect (ADR-018 decision 2) — this is the deliberate, authenticated
     * door instead.
     *
     * <p>Always clears {@code must_change_password}. Whichever path landed a
     * new hash on this row, the temporary credential it was guarding against
     * has just been replaced, so there is nothing left for the flag to gate.
     */
    public int updatePasswordHash(UUID id, String passwordHash) {
        return jdbc.sql("""
                        update account
                        set password_hash = ?, must_change_password = false, updated_at = now()
                        where id = ?
                        """)
                .params(passwordHash, id)
                .update();
    }

    /** A {@code super_admin}'s approve or reject on a pending registration (ADR-014). */
    public int updateStatus(UUID id, AccountStatus status) {
        return jdbc.sql("update account set status = ?::account_status, updated_at = now() where id = ?")
                .params(status.wire(), id)
                .update();
    }

    /** Refreshes the display name from the identity provider on each sign-in. */
    public int updateDisplayName(UUID id, String displayName) {
        return jdbc.sql("""
                        update account set display_name = ?, updated_at = now()
                        where id = ? and display_name <> ?
                        """)
                .params(displayName, id, displayName)
                .update();
    }

    /**
     * Refreshes the profile picture from the identity provider on each sign-in.
     *
     * <p>Guarded on the value actually differing, like
     * {@link #updateDisplayName}: signing in should not write the row and move
     * {@code updated_at} just to store the URL that was already there.
     *
     * <p>A null is written through rather than skipped. When someone removes
     * their Google picture the claim stops arriving, and keeping the old URL
     * would keep displaying an image that person deliberately took down —
     * from a Google URL that will stop resolving anyway.
     *
     * <p>{@code is distinct from}, not {@code <>}: {@code <>} is never true
     * when either side is null, which would make exactly that removal case a
     * silent no-op.
     */
    public int updateAvatarUrl(UUID id, String avatarUrl) {
        return jdbc.sql("""
                        update account set avatar_url = ?, updated_at = now()
                        where id = ? and avatar_url is distinct from ?
                        """)
                .params(avatarUrl, id, avatarUrl)
                .update();
    }

    /**
     * Records a self-uploaded photo (ADR-026). Setting {@code avatar_uploaded_at}
     * is what makes this an owner-controlled value from now on — see
     * {@code SessionService.refreshProfile}, which checks it before writing
     * the identity provider's picture over this on a later sign-in.
     */
    public int updateUploadedAvatar(UUID id, String avatarUrl, Instant uploadedAt) {
        return jdbc.sql("""
                        update account set avatar_url = ?, avatar_uploaded_at = ?, updated_at = now()
                        where id = ?
                        """)
                .params(avatarUrl, OffsetDateTime.ofInstant(uploadedAt, java.time.ZoneOffset.UTC), id)
                .update();
    }

    /**
     * Removes a self-uploaded photo, reverting to initials until the next
     * Google/Keycloak sign-in supplies a provider picture again (ADR-026).
     */
    public int clearUploadedAvatar(UUID id) {
        return jdbc.sql("""
                        update account set avatar_url = null, avatar_uploaded_at = null, updated_at = now()
                        where id = ?
                        """)
                .param(id)
                .update();
    }

    /**
     * Grants one role. Idempotent — granting a role already held is not an
     * error, and must not disturb {@code granted_at}.
     *
     * <p>{@code grantedBy} is null only for roles no human granted: the
     * default role a first sign-in provisions itself.
     */
    public int grantRole(UUID accountId, AppRole role, UUID grantedBy) {
        return jdbc.sql("""
                        insert into account_role (account_id, role_key, granted_by)
                        values (?, ?::app_role, ?)
                        on conflict (account_id, role_key) do nothing
                        """)
                .params(accountId, role.wire(), grantedBy)
                .update();
    }

    /** Revokes one role. Revoking one not held is a no-op, not an error. */
    public int revokeRole(UUID accountId, AppRole role) {
        return jdbc.sql("delete from account_role where account_id = ? and role_key = ?::app_role")
                .params(accountId, role.wire())
                .update();
    }

    /**
     * Replaces the whole set in one transaction.
     *
     * <p>Delete-then-insert rather than a diff. The set is tiny, and the
     * intermediate state is never visible: the {@code super_admin} guard is a
     * deferred constraint trigger, so it is evaluated at commit against the
     * final state, not against the momentary emptiness in between.
     */
    public void replaceRoles(UUID accountId, Set<AppRole> roles, UUID grantedBy) {
        jdbc.sql("delete from account_role where account_id = ?").param(accountId).update();
        for (AppRole role : new LinkedHashSet<>(roles)) {
            grantRole(accountId, role, grantedBy);
        }
    }

    /**
     * Deactivates without removing: authored content keeps its attribution and
     * the person can be let back in. The reversible half of the pair — see
     * {@link #delete} for the other.
     *
     * <p>Also clears {@link #scheduleDeactivation} state: a row this flips to
     * deactivated has nothing left pending, and a row it reactivates
     * (super_admin path) must not still carry a stale self-service schedule
     * from before it was turned off.
     */
    public int setDeactivated(UUID id, boolean deactivated) {
        return jdbc.sql("""
                        update account
                        set deactivated_at = case when ? then now() else null end,
                            deactivation_scheduled_for = null,
                            updated_at = now()
                        where id = ?
                        """)
                .params(deactivated, id)
                .update();
    }

    /**
     * Marks a pending self-service deactivation (revision of ADR-042) — does
     * not touch {@code deactivated_at} itself. The account stays
     * {@link Account#isActive()} (and can still hold roles, appear in admin
     * listings, etc.) until {@code at} is reached and {@link
     * #finalizeScheduledDeactivations} sweeps it, or until a sign-in cancels
     * it first ({@link #cancelScheduledDeactivation}).
     */
    public int scheduleDeactivation(UUID id, Instant at) {
        return jdbc.sql("""
                        update account
                        set deactivation_scheduled_for = ?, updated_at = now()
                        where id = ?
                        """)
                .params(OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC), id)
                .update();
    }

    /**
     * Cancels a pending self-service deactivation without otherwise touching
     * the row — a sign-in that arrives before {@code deactivation_scheduled_for}
     * (revision of ADR-042).
     *
     * <p>Returns rows changed, so the caller ({@code SessionService.issue})
     * can tell "there was nothing pending" from "a pending deactivation was
     * just cancelled" and only audit/notify on the latter.
     */
    public int cancelScheduledDeactivation(UUID id) {
        return jdbc.sql("""
                        update account
                        set deactivation_scheduled_for = null, updated_at = now()
                        where id = ? and deactivation_scheduled_for is not null
                        """)
                .param(id)
                .update();
    }

    /**
     * Every account whose scheduled self-service deactivation is due —
     * {@code ScheduledDeactivationSweeper}'s worklist.
     *
     * <p>{@code deactivated_at is null} excludes a row the sweep already
     * finalized (or that an admin deactivated in the meantime) so a slow
     * poll cycle cannot revisit one twice.
     */
    public List<Account> findDueForScheduledDeactivation(Instant now) {
        return jdbc.sql(SELECT
                        + " where a.deactivation_scheduled_for is not null"
                        + "   and a.deactivation_scheduled_for <= ?"
                        + "   and a.deactivated_at is null"
                        + GROUP_BY)
                .param(OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC))
                .query(MAPPER)
                .list();
    }

    /**
     * Finalizes a due self-service deactivation: turns {@code
     * deactivation_scheduled_for} into a real {@code deactivated_at}, in one
     * statement so no other writer can observe the row with neither set (a
     * row {@link #findDueForScheduledDeactivation} would try to sweep again)
     * or both set.
     */
    public int finalizeScheduledDeactivation(UUID id) {
        return jdbc.sql("""
                        update account
                        set deactivated_at = now(), deactivation_scheduled_for = null, updated_at = now()
                        where id = ?
                        """)
                .param(id)
                .update();
    }

    /**
     * Removes the account row outright (ADR-023).
     *
     * <p>One statement, because the schema already says what everything else
     * should do: sessions, role grants and reset tokens cascade away, while
     * {@code audit_log.actor_id} and {@code page_version.created_by} go null so
     * the trail and the content outlive the person. V10 fixed the one column
     * that did not say — {@code account_role.granted_by} had no ON DELETE
     * action, so PostgreSQL refused the delete for anyone who had ever granted
     * a role, which is every super_admin who ever approved a colleague.
     *
     * <p>The super_admin floor is not enforced here. V4's deferred trigger
     * fires on this DELETE and raises at COMMIT if the floor would be emptied;
     * {@link AccountService} checks first so the caller gets a sentence rather
     * than a constraint violation.
     *
     * <p>Returns rows removed — 0 when the id was already gone.
     */
    public int delete(UUID id) {
        return jdbc.sql("delete from account where id = ?").param(id).update();
    }
}
