package com.coc.docs.account;

import java.util.UUID;

/**
 * An administrator approved or refused a pending registration (ADR-096).
 *
 * <p>Published by {@link AccountService#approve} and {@link
 * AccountService#reject} once the decision is written, and handled after the
 * transaction commits ({@code RegistrationDecisionMailer}), so a decision
 * that rolls back emails nobody.
 *
 * @param role the role granted on approval; null on refusal
 *
 * <p>// req: ADR-096
 */
public record RegistrationDecidedEvent(UUID accountId, String email, String displayName, boolean approved, AppRole role) {}
