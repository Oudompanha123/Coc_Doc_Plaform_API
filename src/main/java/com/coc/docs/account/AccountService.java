package com.coc.docs.account;

import com.coc.docs.audit.AuditAction;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.auth.SessionRepository;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
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

    private final AccountRepository accounts;
    private final SessionRepository sessions;
    private final AuditRepository audit;
    private final ObjectMapper json;

    public AccountService(
            AccountRepository accounts,
            SessionRepository sessions,
            AuditRepository audit,
            ObjectMapper json) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.audit = audit;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<Account> list() {
        return accounts.findAll();
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
    public Account update(UUID accountId, AppRole newRole, Boolean active, CurrentUser actor) {
        Account result = accounts
                .findById(accountId)
                .orElseThrow(() -> ApiException.notFound("account " + accountId));

        if (newRole != null) {
            result = changeRole(accountId, newRole, actor);
        }
        if (active != null) {
            result = setActive(accountId, active, actor);
        }
        return result;
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

    // -----------------------------------------------------------------------
    // Self-registration and approval (ADR-014)
    // -----------------------------------------------------------------------

    /** The queue a {@code super_admin} works through. Oldest first. */
    public List<Account> listPending() {
        return accounts.findByStatus(AccountStatus.PENDING);
    }

    /**
     * Records a self-registration. Creates the account {@code active}
     * immediately — no {@code super_admin} approval gate — per explicit
     * product direction (2026-09-11), reversing the auto-activation
     * ADR-014 explicitly rejected in its "Alternatives Considered" table.
     * See that ADR for the security tradeoff this reopens: anyone reaching
     * this endpoint can grant themselves a working account. Recorded here
     * pending an ADR revision.
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

        Account created = accounts.insertSelfRegisteredAccount(email, displayName, passwordHash);

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
     * <p>No password is set, and that is the point: the person signs in with
     * Google, and the account they land on is this row, matched on email. It
     * exists beforehand so that a role above {@code user} can be waiting for
     * them, rather than an administrator having to catch them after their
     * first sign-in.
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
     * real one arrives from the identity provider on first sign-in and
     * overwrites this, so prompting for it would be asking for a value with a
     * short life.
     *
     * <p>// req: FR-AUTH-003, FR-AUTH-005, FR-AUTH-008, UC-004
     */
    @Transactional
    public Account create(String email, AppRole role, CurrentUser actor) {
        // Checked here as well as by the unique index, so the API can answer
        // 409 with an explanation instead of a raised constraint becoming a 500.
        if (accounts.findByEmail(email).isPresent()) {
            throw ApiException.conflict("that email address already has an account");
        }

        String displayName = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
        Account created = accounts.insert(email, displayName, role, null);

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.ACCOUNT_CREATE,
                AuditAction.TARGET_ACCOUNT,
                created.id(),
                json.createObjectNode()
                        .put("subject", email)
                        .put("role", role.wire())
                        .put("via", "admin-invite"));

        return created;
    }

    /** Approves a pending registration. The account can sign in from the next request. */
    @Transactional
    public Account approve(UUID accountId, CurrentUser actor) {
        return decide(accountId, AccountStatus.ACTIVE, AuditAction.ACCOUNT_APPROVE, actor);
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
