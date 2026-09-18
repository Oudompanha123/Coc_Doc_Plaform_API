package com.coc.docs.capability;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.audit.AuditAction;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grants and revokes capabilities on an account.
 *
 * <p>Kept separate from {@code account.AccountService}, which owns the
 * {@code app_role} ladder's invariants — the last-active-super_admin guard,
 * forced session revocation on a rank drop. Neither applies here: a
 * capability carries no rank, and it resolves fresh per request exactly like
 * {@code Permission} (see {@link CapabilityRepository#findForAccount}), so a
 * revoke takes effect on the caller's very next request with no forced
 * sign-out. Folding this into {@code AccountService} would be the class doing
 * the coupling check being the same class you'd have to audit to prove
 * decoupling holds.
 *
 * <p>// req: ADR-016, ADR-037
 */
@Service
public class CapabilityService {

    private final AccountRepository accounts;
    private final CapabilityRepository capabilities;
    private final AuditRepository audit;
    private final ObjectMapper json;

    public CapabilityService(
            AccountRepository accounts,
            CapabilityRepository capabilities,
            AuditRepository audit,
            ObjectMapper json) {
        this.accounts = accounts;
        this.capabilities = capabilities;
        this.audit = audit;
        this.json = json;
    }

    public Set<Capability> currentFor(UUID accountId) {
        requireAccount(accountId);
        return capabilities.findForAccount(accountId);
    }

    @Transactional
    public Set<Capability> grant(UUID accountId, Capability capability, CurrentUser actor) {
        Account subject = requireAccount(accountId);
        capabilities.grant(accountId, capability, actor.id());

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.ACCOUNT_CAPABILITY_GRANT,
                AuditAction.TARGET_ACCOUNT,
                accountId,
                json.createObjectNode().put("subject", subject.email()).put("capability", capability.key()));

        return capabilities.findForAccount(accountId);
    }

    @Transactional
    public Set<Capability> revoke(UUID accountId, Capability capability, CurrentUser actor) {
        Account subject = requireAccount(accountId);
        capabilities.revoke(accountId, capability);

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.ACCOUNT_CAPABILITY_REVOKE,
                AuditAction.TARGET_ACCOUNT,
                accountId,
                json.createObjectNode().put("subject", subject.email()).put("capability", capability.key()));

        return capabilities.findForAccount(accountId);
    }

    private Account requireAccount(UUID accountId) {
        return accounts.findById(accountId).orElseThrow(() -> ApiException.notFound("account"));
    }
}
