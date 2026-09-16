package com.coc.docs.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * An account signed in from a device it has not been seen on before.
 *
 * <p>Published from the one place a session is written, so neither sign-in
 * path can acquire the behaviour without the other — the same reason
 * {@code AccountRegisteredEvent} is published from the shared insert rather
 * than from each caller that registers somebody.
 *
 * <p>An event rather than a direct call because the notification must not be
 * part of signing in. It is sent after the transaction commits and off the
 * request thread (see {@code SignInNotifier}): a person signing in does not
 * wait on Firebase, and nobody is ever warned about a session that was rolled
 * back.
 *
 * @param accountId whose account was signed into — the recipient, since this
 *     alert goes to the owner and to nobody else
 * @param sessionId the new session, so the alert can offer to end it
 * @param device the readable description, e.g. "Chrome on Windows"
 * @param ipAddress where from, for the in-app detail. Deliberately not put in
 *     the push body, which renders on a lock screen (ADR-025 decision 6)
 * @param signedInAt when
 *
 * <p>// req: FR-AUTH-007, NFR-SEC-SESSION, ADR-027
 */
public record NewDeviceSignInEvent(
        UUID accountId, UUID sessionId, String device, String ipAddress, Instant signedInAt) {}
