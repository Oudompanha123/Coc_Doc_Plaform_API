package com.coc.docs.account;

import com.coc.docs.audit.AuditAction;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.auth.SessionRepository;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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
        Account account = accounts
                .findById(accountId)
                .orElseThrow(() -> ApiException.notFound("account " + accountId));

        if (account.role() == newRole) {
            return account;
        }

        boolean losingSuperAdmin = account.role() == AppRole.SUPER_ADMIN
                && newRole != AppRole.SUPER_ADMIN
                && account.isActive();

        if (losingSuperAdmin && accounts.isLastActiveSuperAdmin(account.id())) {
            throw ApiException.conflict(
                    "this is the only active super admin; promote someone else before demoting "
                            + account.email());
        }

        accounts.updateRole(account.id(), newRole);

        // Only on a demotion. A promotion takes effect on the next request
        // anyway, and signing someone out to give them more authority would be
        // gratuitous.
        int revoked = newRole.isAtLeast(account.role()) ? 0 : sessions.revokeAllFor(account.id());

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.ACCOUNT_ROLE_CHANGE,
                AuditAction.TARGET_ACCOUNT,
                account.id(),
                json.createObjectNode()
                        .put("subject", account.email())
                        .put("from", account.role().wire())
                        .put("to", newRole.wire())
                        .put("sessionsRevoked", revoked));

        return accounts.findById(account.id()).orElseThrow();
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
     * Records a self-registration, awaiting a {@code super_admin}'s decision.
     *
     * <p>The caller passes an already-hashed password. Hashing belongs to the
     * web layer's encoder, not here, so no plaintext ever reaches a class that
     * also writes to the audit trail.
     *
     * <p>The audit entry records the address applied for and nothing else. A
     * registration is an untrusted stranger's claim about themselves until
     * someone approves it, and there is no actor id to attribute it to.
     */
    @Transactional
    public Account register(String email, String displayName, String passwordHash) {
        // Checked here as well as by the unique index, so the API can answer
        // 409 with an explanation instead of a raised constraint becoming a 500.
        if (accounts.findByEmail(email).isPresent()) {
            throw ApiException.conflict("that email address is already registered");
        }

        Account created = accounts.insertPendingRegistration(email, displayName, passwordHash);

        audit.append(
                null,
                email,
                AuditAction.ACCOUNT_REGISTER,
                AuditAction.TARGET_ACCOUNT,
                created.id(),
                json.createObjectNode().put("subject", email).put("status", created.status().wire()));

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
