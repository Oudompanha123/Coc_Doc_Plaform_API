package com.coc.docs.account;

import java.util.UUID;

/**
 * Somebody has applied for an account, and the row exists.
 *
 * <p>Published from {@code AccountRepository.insertApplication} — the one
 * statement both registration doors pass through. The password path reaches it
 * from {@code AccountService.register} once the emailed code is confirmed
 * (ADR-021); the Google path reaches it from {@code SessionService} on a first
 * sign-in. Publishing from the two call sites instead would leave a third door
 * free to forget, which is a mistake this project has already made once: the
 * approval gate had to be fixed in both doors for exactly that reason.
 *
 * <p>Carries no {@code Account}, only what a listener needs. An event that
 * hands out a mutable-looking snapshot invites listeners to act on a state that
 * may already have moved on.
 *
 * <p>// req: ADR-017, ADR-025
 */
public record AccountRegisteredEvent(UUID accountId, String email) {}
