package com.coc.docs.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * Somebody signed in. Every time, not only from a new device (ADR-086).
 *
 * <p>Distinct from {@link NewDeviceSignInEvent}, which exists to warn a person
 * that an unfamiliar device used their account and is therefore rare by
 * design. This one fires on every successful sign-in, so anything that wants
 * to act on "they are here now" has something to listen to without weakening
 * the security alert into a general-purpose notification.
 *
 * <p>Carries no session token and no credential: a listener runs off the
 * request thread, and an event that travelled with one would put it somewhere
 * nobody is watching.
 *
 * @param accountId who signed in
 * @param email their address, so a listener need not re-read the account
 * @param at when, from the session that was issued
 *
 * <p>// req: FR-AUTH-001, NFR-SEC-PII, ADR-086
 */
public record SignInEvent(UUID accountId, String email, Instant at) {}
