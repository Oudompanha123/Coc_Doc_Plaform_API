package com.coc.docs.account;

import com.coc.docs.audit.AuditAction;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.AccountInviteMailer;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.auth.SessionRepository;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.util.List;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account administration. Reachable only by a super admin — the filter chain
 * puts a fixed floor on {@code /api/v1/admin/accounts/**} per FR-AUTH-008.
 *
 * <p>// req: FR-AUTH-003, FR-AUTH-005, FR-AUTH-008, FR-OPS-001
 */
@Service
public class AccountService {

    /** The temporary password's length. Long enough that entropy, not the character set, does the work. */
    private static final int TEMP_PASSWORD_LENGTH = 14;

    /**
     * Unambiguous on purpose: no {@code 0/O} or {@code 1/l/I}, so a person
     * retyping this from an email or a screen is not defeated by a font that
     * renders them alike.
     */
    private static final String TEMP_PASSWORD_ALPHABET =
            "ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#$%";

    private final AccountRepository accounts;
    private final SessionRepository sessions;
    private final AuditRepository audit;
    private final ObjectMapper json;
    private final PasswordEncoder passwordEncoder;
    private final AccountInviteMailer inviteMailer;
    private final SecureRandom random = new SecureRandom();

    public AccountService(
            AccountRepository accounts,
            SessionRepository sessions,
            AuditRepository audit,
            ObjectMapper json,
            PasswordEncoder passwordEncoder,
            AccountInviteMailer inviteMailer) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.audit = audit;
        this.json = json;
        this.passwordEncoder = passwordEncoder;
        this.inviteMailer = inviteMailer;
    }

    /**
     * The Users screen: every account a {@code super_admin} has actually
     * decided on.
     *
     * <p>A self-registration sits in {@link AccountStatus#PENDING} until it is
     * approved or rejected (ADR-014); listing it here too would show an
     * undecided application as if it were already a user, on a row that offers
     * Deactivate and Delete for a decision nobody has made yet. It moves into
     * this list the moment {@link #approve} takes it out of the queue.
     *
     * <p>// req: FR-AUTH-003, ADR-014
     */
    @Transactional(readOnly = true)
    public List<Account> list() {
        return accounts.findAll().stream()
                .filter(account -> account.status() != AccountStatus.PENDING)
                .toList();
    }

    @Transactional(readOnly = true)
    public Account require(UUID accountId) {
        return accounts
                .findById(accountId)
                .orElseThrow(() -> ApiException.notFound("account " + accountId));
    }

    /**
     * Applies a role change, an activation change, or both, as one transaction.
     *
     * <p>Both in one transaction because a PATCH carrying both is one intent. Run
     * as two calls, a request that demotes and deactivates the same account
     * commits the demotion and then fails the deactivation on the last-super-admin
     * guard — leaving the caller with an error response and a role change they
     * were not told about. Either both land or neither does.
     *
     * <p>Order matters: the role change goes first, so demoting the only super
     * admin is refused by the same guard whichever field triggers it.
     *
     * <p>// req: FR-AUTH-003, FR-AUTH-004, FR-AUTH-005, UC-004
     */
    @Transactional
    public Account update(
            UUID accountId, AppRole newRole, Boolean active, String displayName, CurrentUser actor) {

        Account result = accounts
                .findById(accountId)
                .orElseThrow(() -> ApiException.notFound("account " + accountId));

        if (newRole != null) {
            result = changeRole(accountId, newRole, actor);
        }
        if (active != null) {
            result = setActive(accountId, active, actor);
        }
        if (displayName != null) {
            result = renameFor(accountId, displayName, actor);
        }
        return result;
    }

    /**
     * Corrects an account's display name.
     *
     * <p>Worth knowing before using it: for anyone who signs in with Google,
     * this is temporary. Sign-in refreshes the name from the identity provider,
     * so an administrator's correction survives only until that person next
     * signs in. It is genuinely useful for an account created by invite, which
     * carries an address-derived placeholder until its owner first arrives.
     *
     * <p>Not audited as a security event, unlike role and activation changes:
     * a display name grants nothing. The row's {@code updated_at} moves, which
     * is the trail that matches the weight of the change.
     */
    @Transactional
    public Account renameFor(UUID accountId, String displayName, CurrentUser actor) {
        String trimmed = displayName.trim();
        if (trimmed.isEmpty()) {
            throw ApiException.badRequest("a display name cannot be blank");
        }
        accounts.updateDisplayName(accountId, trimmed);
        return accounts.findById(accountId).orElseThrow();
    }

    /**
     * Changes a role.
     *
     * <p>Two rules, both of which exist because of how the system fails without
     * them:
     *
     * <ul>
     *   <li>Demoting the last active super admin is refused, or the system locks
     *       itself out of its own account management (UC-004 E-4). The schema
     *       holds the same rule as a deferred constraint trigger — that is the
     *       control; this check exists so the caller gets an explanation rather
     *       than a 500 carrying a raised Postgres exception.
     *   <li>A demotion revokes the account's live sessions. The role is read from
     *       the database on every request, but only for as long as a session
     *       points at the account, so without this a removed admin keeps admin
     *       authority until their session happens to expire.
     * </ul>
     *
     * <p>// req: FR-AUTH-005, FR-AUTH-008, UC-004
     */
    @Transactional
    public Account changeRole(UUID accountId, AppRole newRole, CurrentUser actor) {
        return setRoles(accountId, EnumSet.of(newRole), actor);
    }

    /**
     * Replaces the set of roles an account holds (ADR-016).
     *
     * <p>{@link #changeRole} is this with a set of one, kept because the admin
     * API still speaks in a single role and because most changes really are
     * "make this person an admin".
     *
     * <p>An empty set is refused. It is reachable in the schema — a row in
     * {@code account} with nothing in {@code account_role} — and
     * {@link Account#role()} correctly reports {@code guest} for it, but
     * arriving there through this method is almost always a mistake in the
     * caller rather than an intent, and the resulting account cannot be told
     * apart from a broken one. Deactivate the account instead; that says what
     * was meant and is reversible in one step.
     *
     * <p>// req: FR-AUTH-005, FR-AUTH-008, UC-004
     */
    @Transactional
    public Account setRoles(UUID accountId, Set<AppRole> newRoles, CurrentUser actor) {
        Account account = accounts
                .findById(accountId)
                .orElseThrow(() -> ApiException.notFound("account " + accountId));

        if (newRoles == null || newRoles.isEmpty()) {
            throw ApiException.badRequest(
                    "an account must hold at least one role; deactivate it instead of removing them all");
        }

        Set<AppRole> current = account.roles();
        if (current.equals(newRoles)) {
            return account;
        }

        // The floor is about the super_admin role specifically, not about rank:
        // losing it is what removes the ability to grant roles back.
        boolean losingSuperAdmin = current.contains(AppRole.SUPER_ADMIN)
                && !newRoles.contains(AppRole.SUPER_ADMIN)
                && account.isActive();

        if (losingSuperAdmin && accounts.isLastActiveSuperAdmin(account.id())) {
            throw ApiException.conflict(
                    "this is the only active super admin; promote someone else before demoting "
                            + account.email());
        }

        accounts.replaceRoles(account.id(), newRoles, actor.id());

        // Only when rank drops. A promotion takes effect on the next request
        // anyway, and signing someone out to give them more authority would be
        // gratuitous. Compared on the highest rank held, because that is what
        // every authorisation check reads.
        AppRole before = account.role();
        AppRole after = account.withRoles(newRoles).role();
        int revoked = after.isAtLeast(before) ? 0 : sessions.revokeAllFor(account.id());

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.ACCOUNT_ROLE_CHANGE,
                AuditAction.TARGET_ACCOUNT,
                account.id(),
                json.createObjectNode()
                        .put("subject", account.email())
                        .put("from", wire(current))
                        .put("to", wire(newRoles))
                        .put("sessionsRevoked", revoked));

        return accounts.findById(account.id()).orElseThrow();
    }

    /** Role sets in the audit trail, ordered by rank so two equal sets always read identically. */
    private static String wire(Set<AppRole> roles) {
        return roles.stream()
                .sorted(java.util.Comparator.comparingInt(Enum::ordinal))
                .map(AppRole::wire)
                .collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * Deactivates or reactivates an account.
     *
     * <p>Deactivation revokes every live session, because an account that can
     * still act until its session expires has not been deactivated in any sense
     * an auditor would accept.
     *
     * <p>// req: FR-AUTH-003
     */
    @Transactional
    public Account setActive(UUID accountId, boolean active, CurrentUser actor) {
        Account account = accounts
                .findById(accountId)
                .orElseThrow(() -> ApiException.notFound("account " + accountId));

        if (account.isActive() == active) {
            return account;
        }

        if (!active) {
            if (account.id().equals(actor.id())) {
                // Not a security boundary — a super admin could deactivate a
                // colleague and then themselves. It is refused because it is
                // almost always a misclick, and the recovery is another person.
                throw ApiException.badRequest("you cannot deactivate your own account");
            }
            if (account.role() == AppRole.SUPER_ADMIN
                    && accounts.isLastActiveSuperAdmin(account.id())) {
                throw ApiException.conflict(
                        "this is the only active super admin; promote someone else before "
                                + "deactivating " + account.email());
            }
        }

        accounts.setDeactivated(account.id(), !active);
        int revoked = active ? 0 : sessions.revokeAllFor(account.id());

        audit.append(
                actor.id(),
                actor.email(),
                active ? AuditAction.ACCOUNT_REACTIVATE : AuditAction.ACCOUNT_DEACTIVATE,
                AuditAction.TARGET_ACCOUNT,
                account.id(),
                json.createObjectNode()
                        .put("subject", account.email())
                        .put("sessionsRevoked", revoked));

        return accounts.findById(account.id()).orElseThrow();
    }

    /**
     * Removes an account outright (ADR-023).
     *
     * <p>The irreversible sibling of {@link #setActive}. Deactivation is the
     * right tool almost always — it keeps the person's name against their
     * work and lets them back in later. Deletion exists for the cases
     * deactivation cannot answer: a typo'd invite, a duplicate row, a
     * contractor whose record should not persist past the engagement.
     *
     * <p>What survives on purpose: every {@code audit_log} entry, including
     * the ones this account wrote. Its {@code actor_id} goes null and
     * {@code actor_email} — denormalised in V1 for exactly this — keeps
     * answering "who did this" a year later (NFR-OPS-AUDIT). Published content
     * survives too, with {@code page_version.created_by} nulled.
     *
     * <p>What goes with it: sessions, role grants and any unspent reset token,
     * all by cascade. A deleted account cannot finish a request it had already
     * started.
     *
     * <p>Audited <b>before</b> the delete, and the entry carries the subject's
     * email and roles inline. Afterwards there is no row to join to, so an
     * entry naming only a UUID would be a record of nothing.
     *
     * <p>// req: FR-AUTH-003, FR-AUTH-008, ADR-023
     */
    @Transactional
    public void delete(UUID accountId, CurrentUser actor) {
        Account account = accounts
                .findById(accountId)
                .orElseThrow(() -> ApiException.notFound("account " + accountId));

        if (account.id().equals(actor.id())) {
            // Refused for a stronger reason than self-deactivation is. That
            // one is recoverable by a colleague; this one removes the account
            // making the request, mid-request, and there is nothing to undo.
            throw ApiException.badRequest("you cannot delete your own account");
        }

        if (account.roles().contains(AppRole.SUPER_ADMIN)
                && accounts.isLastActiveSuperAdmin(account.id())) {
            // V4's deferred trigger would catch this at COMMIT regardless, but
            // as a constraint violation naming a trigger. Checked here so the
            // answer is a sentence that says what to do about it.
            throw ApiException.conflict(
                    "this is the only active super admin; promote someone else before "
                            + "deleting " + account.email());
        }

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.ACCOUNT_DELETE,
                AuditAction.TARGET_ACCOUNT,
                account.id(),
                json.createObjectNode()
                        .put("subject", account.email())
                        .put("displayName", account.displayName())
                        .put("status", account.status().wire())
                        .put("roles", account.roles().stream().map(AppRole::wire).sorted().toList().toString()));

        accounts.delete(account.id());
    }

    // -----------------------------------------------------------------------
    // Self-registration and approval (ADR-014)
    // -----------------------------------------------------------------------

    /** The queue a {@code super_admin} works through. Oldest first. */
    public List<Account> listPending() {
        return accounts.findByStatus(AccountStatus.PENDING);
    }

    /**
     * Records a self-registration. Creates the account {@code pending} at
     * role {@code guest} (ADR-017) — an application, not an account that
     * works, restoring the approval gate ADR-014 specified and a brief
     * 2026-09-11 product direction had removed.
     *
     * <p>Called from two places, both after the caller's control of the
     * email address is already established: directly, when an
     * administrator is not the one creating the account (see {@link
     * #create} for that path instead); and — since ADR-021 — from {@code
     * RegistrationOtpService.verify}, which is now the only caller in
     * practice, once the emailed 6-digit code has been confirmed. This
     * method itself is unchanged by ADR-021; it has no idea a code was ever
     * involved.
     *
     * <p>The caller passes an already-hashed password. Hashing belongs to the
     * web layer's encoder, not here, so no plaintext ever reaches a class that
     * also writes to the audit trail.
     *
     * <p>The audit entry records the address applied for and nothing else —
     * there is no actor id to attribute a self-registration to.
     */
    @Transactional
    public Account register(String email, String displayName, String passwordHash) {
        // Checked here as well as by the unique index, so the API can answer
        // 409 with an explanation instead of a raised constraint becoming a 500.
        if (accounts.findByEmail(email).isPresent()) {
            throw ApiException.conflict("that email address is already registered");
        }

        // Pending, at role guest (ADR-017). Between 2026-09-11 and this change
        // registration produced an active account immediately, which meant the
        // Google gate would have had an open door beside it: anyone could
        // self-issue a working account by choosing a password instead. Both
        // doors now lead to the same queue.
        Account created = accounts.insertApplication(email, displayName, null, passwordHash);

        audit.append(
                null,
                email,
                AuditAction.ACCOUNT_REGISTER,
                AuditAction.TARGET_ACCOUNT,
                created.id(),
                json.createObjectNode().put("subject", email).put("status", created.status().wire()));

        return created;
    }

    /**
     * Creates an account on behalf of a {@code super_admin} — the "add account"
     * path in the administration screen.
     *
     * <p>Issues a temporary password and emails it (revision of ADR-017): the
     * account is usable immediately over the password sign-in path, and its
     * owner is required to replace that password before doing anything else
     * (see {@code SessionService.changeOwnPassword} and the
     * {@code must_change_password} gate). Google sign-in remains available too
     * — this does not replace that path, it adds a working credential for
     * someone who has not signed in with Google yet, matched on the same
     * email.
     *
     * <p>Active immediately. There is nothing to approve — a
     * {@code super_admin} creating it <em>is</em> the approval, unlike
     * self-registration where the applicant is unknown.
     *
     * <p>The role comes from an authenticated {@code super_admin}, which is
     * what separates this from {@link #register}: there, role is a literal
     * because the caller is anonymous and could otherwise ask for
     * {@code super_admin} (TM-025); here, the caller already holds it.
     *
     * <p>Display name is derived from the address rather than asked for. The
     * real one arrives from the identity provider on first Google sign-in and
     * overwrites this, so prompting for it would be asking for a value with a
     * short life.
     *
     * <p>The plaintext temporary password exists only in this method's local
     * variable and inside {@link AccountInviteMailer}, which puts it in an
     * email and nowhere else; it is never logged, never audited and never
     * returned in the API response — only its bcrypt hash reaches the
     * database.
     *
     * <p>// req: FR-AUTH-002, FR-AUTH-003, FR-AUTH-005, FR-AUTH-008, UC-004, ADR-017
     */
    @Transactional
    public Account create(String email, AppRole role, CurrentUser actor) {
        // Checked here as well as by the unique index, so the API can answer
        // 409 with an explanation instead of a raised constraint becoming a 500.
        if (accounts.findByEmail(email).isPresent()) {
            throw ApiException.conflict("that email address already has an account");
        }

        String displayName = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
        String temporaryPassword = generateTemporaryPassword();

        // Active, not pending: a super_admin creating this account is the
        // approval. Sending it to the queue would ask them to approve their own
        // decision (ADR-017).
        Account created = accounts.insert(
                email, displayName, role, null, AccountStatus.ACTIVE,
                passwordEncoder.encode(temporaryPassword), true);

        inviteMailer.sendTemporaryPassword(email, temporaryPassword);

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.ACCOUNT_CREATE,
                AuditAction.TARGET_ACCOUNT,
                created.id(),
                json.createObjectNode()
                        .put("subject", email)
                        .put("role", role.wire())
                        .put("via", "admin-invite")
                        .put("temporaryPasswordEmailed", true));

        return created;
    }

    /**
     * A random temporary password for a {@code super_admin} invite.
     *
     * <p>{@link SecureRandom}, not {@link java.util.Random} — this value is a
     * working credential until its owner replaces it, the same standard every
     * other token in this codebase (session, nonce, reset) holds itself to.
     * The alphabet excludes characters that are easy to mistype or confuse
     * across fonts, because unlike those other tokens, a person has to
     * actually retype this one.
     */
    private String generateTemporaryPassword() {
        StringBuilder password = new StringBuilder(TEMP_PASSWORD_LENGTH);
        for (int i = 0; i < TEMP_PASSWORD_LENGTH; i++) {
            password.append(TEMP_PASSWORD_ALPHABET.charAt(random.nextInt(TEMP_PASSWORD_ALPHABET.length())));
        }
        return password.toString();
    }

    /** Approves a pending registration. The account can sign in from the next request. */
    /**
     * Lets a pending applicant in, at a role chosen now (ADR-017).
     *
     * <p>One decision, not two. "Should this person be let in, and as what" is
     * a single thought; approve-then-promote splits it across two screens and
     * two audit entries, and leaves a window in which someone is inside at a
     * role nobody chose.
     *
     * <p>The granted role replaces {@code guest}, which an applicant holds
     * while pending and which grants nothing. {@code guest} itself is refused:
     * approving somebody into a role that opens nothing is not an approval,
     * and the caller almost certainly meant to reject.
     */
    @Transactional
    public Account approve(UUID accountId, AppRole role, CurrentUser actor) {
        if (role == AppRole.GUEST) {
            throw ApiException.badRequest(
                    "guest grants nothing; approve at user or above, or reject the application");
        }
        Account approved = decide(accountId, AccountStatus.ACTIVE, AuditAction.ACCOUNT_APPROVE, actor);
        return setRoles(approved.id(), EnumSet.of(role), actor);
    }

    /**
     * Rejects a pending registration.
     *
     * <p>Rejected rather than deleted, for the same reason accounts are
     * deactivated rather than deleted: the audit trail has to be able to answer
     * "who applied, and who turned them down" a year later (NFR-OPS-AUDIT).
     */
    @Transactional
    public Account reject(UUID accountId, CurrentUser actor) {
        return decide(accountId, AccountStatus.REJECTED, AuditAction.ACCOUNT_REJECT, actor);
    }

    private Account decide(UUID accountId, AccountStatus target, String action, CurrentUser actor) {
        Account account = require(accountId);

        // Only a pending registration is a decision to make. Re-approving an
        // active account, or reversing a rejection, is an account change and
        // belongs on the PATCH path where it is audited as one.
        if (account.status() != AccountStatus.PENDING) {
            throw ApiException.conflict(
                    "account " + account.email() + " is " + account.status().wire() + ", not pending");
        }

        accounts.updateStatus(account.id(), target);

        audit.append(
                actor.id(),
                actor.email(),
                action,
                AuditAction.TARGET_ACCOUNT,
                account.id(),
                json.createObjectNode().put("subject", account.email()).put("status", target.wire()));

        return accounts.findById(account.id()).orElseThrow();
    }
}
