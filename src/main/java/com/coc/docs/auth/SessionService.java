package com.coc.docs.auth;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountCredential;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import com.coc.docs.account.AvatarStorage;
import com.coc.docs.audit.AuditAction;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.config.AppProperties;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, resolves and revokes sessions.
 *
 * <p>This is the chain of custody ADR-011 describes: Google verifies the
 * identity, this class issues a credential of its own bound to an account row,
 * and every later request is authorised against the role read from that row.
 * Nothing the client sends contributes to the role.
 *
 * <p>// req: FR-AUTH-001, FR-AUTH-004, FR-AUTH-005, FR-AUTH-007, NFR-SEC-SESSION
 */
@Service
public class SessionService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SessionService.class);

    /**
     * 32 bytes of {@link SecureRandom}, base64url-encoded. Opaque and
     * unguessable; the token carries no claims, so there is nothing in it to
     * tamper with and no signature to verify.
     */
    private static final int TOKEN_BYTES = 32;

    public static final String COOKIE_NAME = "coc_session";

    /**
     * How long a post-password MFA challenge (ADR-031) stays redeemable —
     * long enough to switch to an authenticator app and type a code, short
     * enough not to sit as a standing, password-already-proven credential.
     */
    private static final Duration MFA_CHALLENGE_TTL = Duration.ofMinutes(5);

    private final SessionRepository sessions;
    private final AccountRepository accounts;
    private final AuditRepository audit;
    private final GoogleIdTokenVerifier google;

    /** TOTP enrollment lookups for the password sign-in path (ADR-031) — no dependency the other direction: see {@link TotpService}'s class Javadoc. */
    private final AccountTotpRepository totps;

    /** Mints the pending-MFA challenge {@link TotpService#verifyLoginChallenge} redeems (ADR-031). */
    private final MfaLoginChallengeRepository mfaChallenges;

    /**
     * The ADR-013 broker, present only when a Keycloak realm is configured.
     * An {@link ObjectProvider} rather than a required dependency, because a
     * deployment without Keycloak must still boot and serve the reader path.
     */
    private final ObjectProvider<KeycloakIdTokenService> keycloak;

    /**
     * Present only when object storage is actually configured
     * (CONST-TECH-08, ADR-012 §2 — still {@code [DEFERRED]}). The same
     * optional-bean shape as {@link #keycloak}: a deployment that has not
     * chosen a provider yet must still boot and serve every other route.
     */
    private final ObjectProvider<AvatarStorage> avatarStorage;
    private final AppProperties properties;
    private final ObjectMapper json;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /**
     * A valid bcrypt digest of a value nothing can supply, used so that an
     * unknown email costs the same work as a known one. Without it, the miss
     * path skips the hash and answers measurably faster — which discloses
     * which addresses are registered just as surely as a different message.
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final PasswordEncoder passwordEncoder;

    /**
     * Announces a sign-in from an unrecognised device, without this class
     * knowing who hears about it or how.
     *
     * <p>The same shape {@code AccountRepository} uses for registration: the
     * thing that happened is published where it happens, and the notifier is a
     * listener that sign-in does not wait for and cannot be broken by.
     */
    private final ApplicationEventPublisher events;

    public SessionService(
            SessionRepository sessions,
            AccountRepository accounts,
            AuditRepository audit,
            GoogleIdTokenVerifier google,
            ObjectProvider<KeycloakIdTokenService> keycloak,
            ObjectProvider<AvatarStorage> avatarStorage,
            AppProperties properties,
            ObjectMapper json,
            Clock clock,
            PasswordEncoder passwordEncoder,
            AccountTotpRepository totps,
            MfaLoginChallengeRepository mfaChallenges,
            ApplicationEventPublisher events) {
        this.sessions = sessions;
        this.accounts = accounts;
        this.audit = audit;
        this.google = google;
        this.keycloak = keycloak;
        this.avatarStorage = avatarStorage;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
        this.passwordEncoder = passwordEncoder;
        this.totps = totps;
        this.mfaChallenges = mfaChallenges;
        this.events = events;
    }

    /**
     * What {@link #signInWithPassword} returns (ADR-031): either a real
     * session, or notice that a TOTP code is still needed. Sealed rather
     * than a boolean-plus-nullable-field pair, so {@code AuthController}'s
     * {@code switch} is exhaustive and the compiler — not a runtime null
     * check — enforces that every case is handled.
     */
    public sealed interface PasswordSignInResult permits IssuedSession, MfaRequired {}

    /**
     * An issued session: the plaintext token, which exists only here and in
     * the response.
     *
     * <p>{@code deactivationCancelled} (revision of ADR-042): true when this
     * sign-in itself just cancelled a pending self-service deactivation —
     * {@code AuthController} surfaces it on {@code MeResponse} so the
     * frontend can tell the caller their account is no longer scheduled to
     * deactivate, rather than that being silently true.
     */
    public record IssuedSession(String token, Account account, Instant expiresAt, boolean deactivationCancelled)
            implements PasswordSignInResult {}

    /**
     * The password was correct, but this account has confirmed TOTP
     * (ADR-031) — no session yet. {@code challengeToken} is the plaintext
     * value {@code AuthController} sets as the {@code coc_mfa_pending}
     * cookie; only its hash is persisted ({@link #mintMfaChallenge}).
     */
    public record MfaRequired(String challengeToken, Instant expiresAt) implements PasswordSignInResult {}

    // -----------------------------------------------------------------------
    // Sign in
    // -----------------------------------------------------------------------

    /**
     * Verifies a Google ID token and issues a session.
     *
     * <p>A first-time signer-in does not get in. They get an account row at
     * status pending and role guest, and are turned away until a super admin
     * approves them (ADR-017). Google vouches that the address is real, not
     * that its owner belongs here.
     *
     * <p>The role is never taken from the sign-in, so signing in cannot be a
     * route to privilege.
     *
     * <p>A deactivated account is refused. Deactivation has to end access
     * immediately, or it is a note in a table rather than a control.
     *
     * <p><b>Why noRollbackFor.</b> This method reports two of its outcomes by
     * throwing, and both have to leave something behind.
     *
     * <p>An applicant is told they are awaiting approval — and the application
     * row that makes the queue work is created moments earlier, in this same
     * transaction. Rolling back deleted it, so the person was told to wait for
     * approval of a record that no longer existed and the queue stayed empty.
     *
     * <p>A rejected sign-in is written to the audit log before the exception is
     * rethrown. Rolling back deleted that too: auth.rejected had never once
     * been persisted, across every refusal this system has ever issued.
     *
     * <p>The default is right for most services and wrong here, because these
     * exceptions report a decision rather than a failure. A genuine failure —
     * a broken query, a constraint violation — is not listed and still rolls
     * back.
     *
     * <p>// req: FR-AUTH-001, FR-AUTH-003, FR-AUTH-005, FR-OPS-002, ADR-017
     */
    @Transactional(
            noRollbackFor = {
                com.coc.docs.web.ApiException.class,
                GoogleIdTokenVerifier.AuthenticationFailure.class
            })
    public IssuedSession signIn(
            String idToken, String expectedNonce, String userAgent, String ipAddress) {
        VerifiedIdentity identity;
        try {
            identity = verifyIdToken(idToken, expectedNonce);
        } catch (GoogleIdTokenVerifier.AuthenticationFailure | AuthenticationFailedException e) {
            // Recorded without an actor id, because there is no verified actor.
            ObjectNode detail = json.createObjectNode().put("reason", e.getMessage());
            audit.append(
                    null, "unauthenticated", AuditAction.AUTH_REJECTED,
                    AuditAction.TARGET_SESSION, null, detail);
            throw e;
        }

        Account account = accounts
                .findByEmail(identity.email())
                .map(existing -> refreshProfile(existing, identity))
                .orElseGet(() -> createAccount(identity));

        // The line above is where "apply with Google" happens. There is no
        // separate registration endpoint for it: a first sign-in creates the
        // application, and the check below then turns that person away until
        // somebody approves them. Two endpoints would mean two places deciding
        // what a new account may do.

        // canSignIn, not isActive: someone who self-registered and is still
        // awaiting approval must not get in through the Google door instead
        // (ADR-014). Same email, same account row, one gate.
        if (!account.canSignIn()) {
            // A pending applicant is told so, and this is the one sign-in
            // failure in the system that is not deliberately opaque. They have
            // just proved they own this Google address, so naming their own
            // status discloses nothing to anyone else — and the alternative is
            // a generic error after an action they took on purpose, which
            // reads as the product being broken.
            //
            // The password path stays uniform: there, the caller has proved
            // nothing, and a distinguishable answer would turn sign-in into a
            // way to discover who has applied.
            if (account.status() == AccountStatus.PENDING) {
                throw ApiException.forbidden("this registration is awaiting approval");
            }
            throw new GoogleIdTokenVerifier.AuthenticationFailure("this account cannot sign in");
        }

        // Both sign-in paths write a session the same way, through one method.
        // They did not always: this one carried its own copy of the insert and
        // the audit entry, so a change made to the shared method — the device
        // check below, say — would have been absent from exactly the path most
        // people use. The audit entry gains the "via" it did not have before,
        // which is the whole difference.
        return issue(account, userAgent, ipAddress, "google");
    }

    /**
     * Brings the display name and profile picture back in step with the
     * identity provider on every sign-in.
     *
     * <p>Only what the provider owns. Role, status and deactivation belong to
     * this system and are never touched here — signing in must not be able to
     * change what someone may do.
     *
     * <p><b>Except the picture, once its owner has uploaded one of their own
     * (ADR-026).</b> {@code avatarUploadedAt} being set means a person made a
     * deliberate choice through "My Profile"; a sign-in — an action about
     * proving identity, not about editing a profile — must not silently
     * undo it. They stay in control of it until they remove it themselves
     * ({@link #removeOwnAvatar}), at which point the provider's picture is
     * free to fill back in on the next sign-in, same as before this existed.
     */
    private Account refreshProfile(Account existing, VerifiedIdentity identity) {
        String displayName = identity.displayName();
        boolean nameChanged = !existing.displayName().equals(displayName);

        boolean avatarOwnerControlled = existing.avatarUploadedAt() != null;
        String avatarUrl = avatarOwnerControlled ? existing.avatarUrl() : identity.avatarUrl();
        boolean avatarChanged = !avatarOwnerControlled
                && !java.util.Objects.equals(existing.avatarUrl(), avatarUrl);

        if (!nameChanged && !avatarChanged) {
            return existing;
        }
        if (nameChanged) {
            accounts.updateDisplayName(existing.id(), displayName);
        }
        if (avatarChanged) {
            accounts.updateAvatarUrl(existing.id(), avatarUrl);
        }

        return new Account(
                existing.id(),
                existing.email(),
                displayName,
                existing.roles(),
                avatarUrl,
                existing.avatarUploadedAt(),
                existing.status(),
                existing.deactivatedAt(),
                existing.deactivationScheduledFor(),
                existing.createdAt(),
                existing.updatedAt(),
                existing.mustChangePassword(),
                existing.hasPassword());
    }

    /**
     * Registration by Google, which is simply a first sign-in.
     *
     * <p><b>Role is the literal {@link AppRole#USER}</b>, never anything the
     * token carried. Whoever the identity provider says you are, arriving here
     * for the first time buys read access and nothing else; everything above
     * that is granted by a {@code super_admin} afterwards. This is the same
     * rule the password registration path follows, and it is what keeps
     * account creation off the privilege-escalation path (TM-025).
     */
    private Account createAccount(VerifiedIdentity identity) {
        // THE BOOTSTRAP. With every applicant starting pending, an installation
        // holding no accounts is a locked door: nobody can be approved because
        // nobody exists to approve them. The first person through is therefore
        // admitted as an active super_admin.
        //
        // Narrow on purpose — `account` completely empty, not "no super admin
        // exists". The weaker test would hand the role to whoever signed in
        // next any time the last administrator was deactivated, which is
        // precisely when it must not. This can be true only once in an
        // installation's life, and it matters now because every developer gets
        // their own empty schema (DB_SCHEMA).
        boolean bootstrap = accounts.isEmpty();

        Account created = bootstrap
                ? accounts.insert(
                        identity.email(),
                        identity.displayName(),
                        AppRole.SUPER_ADMIN,
                        identity.avatarUrl(),
                        AccountStatus.ACTIVE)
                : accounts.insertApplication(
                        identity.email(), identity.displayName(), identity.avatarUrl(), null);

        audit.append(
                created.id(),
                created.email(),
                AuditAction.ACCOUNT_CREATE,
                AuditAction.TARGET_ACCOUNT,
                created.id(),
                json.createObjectNode()
                        .put("role", created.role().wire())
                        .put("status", created.status().wire())
                        .put("via", bootstrap ? "google-sign-in-bootstrap" : "google-sign-in"));
        return created;
    }

    /**
     * Verifies the ID token with whichever identity the deployment trusts.
     *
     * <p>ADR-013 moved that trust to Keycloak, which brokers Google upstream.
     * The Keycloak verifier only exists when a realm is configured
     * ({@code app.keycloak.issuer-uri}); where it is not, this falls back to
     * verifying a Google-issued token directly, which is what ADR-008
     * originally specified.
     *
     * <p>The fallback is not a permissive default. Both paths verify a
     * signature, an issuer and an audience; they differ only in whose keys
     * they trust. What the fallback cannot do is check the nonce — Google's
     * own ID token carries one only if the client requested it, and this
     * project's direct-Google path never did. That is stated here rather than
     * hidden, because it means replay protection (TM-001) arrives with
     * Keycloak and not before.
     */
    private VerifiedIdentity verifyIdToken(String idToken, String expectedNonce) {
        KeycloakIdTokenService broker = keycloak.getIfAvailable();
        if (broker != null) {
            return broker.verify(idToken, expectedNonce);
        }
        GoogleIdTokenVerifier.GoogleIdentity google = this.google.verify(idToken);
        // The direct-Google fallback carries no picture: this path never
        // requested the profile scope, so there is no claim to read.
        return new VerifiedIdentity(google.email(), google.displayName(), null);
    }

    // -----------------------------------------------------------------------
    // Resolve
    // -----------------------------------------------------------------------

    /**
     * Resolves a presented token to the caller, or empty if it is not usable.
     *
     * <p>The account — and therefore the role — is read on every request rather
     * than cached in the session row. A role change or a deactivation then takes
     * effect on the caller's next request, not when their session happens to
     * expire.
     *
     * <p>// req: FR-AUTH-005, TM-003
     */
    @Transactional(readOnly = true)
    public Optional<CurrentUser> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        byte[] tokenHash = hash(token);

        Optional<AppSession> found = sessions.find(tokenHash);
        if (found.isEmpty() || !found.get().isLive(clock.instant())) {
            return Optional.empty();
        }

        return accounts
                .findById(found.get().accountId())
                .filter(Account::isActive)
                .map(account -> new CurrentUser(account, accounts.findPermissions(account.id()), tokenHash));
    }

    // -----------------------------------------------------------------------
    // Sign out
    // -----------------------------------------------------------------------

    /**
     * Revokes a session server-side.
     *
     * <p>// req: FR-AUTH-007
     */
    @Transactional
    public void signOut(CurrentUser caller) {
        sessions.revoke(caller.sessionTokenHash());
        audit.append(
                caller.account().id(),
                caller.account().email(),
                AuditAction.AUTH_SIGN_OUT,
                AuditAction.TARGET_SESSION,
                caller.account().id(),
                json.createObjectNode());
    }

    /**
     * Ends the session a new sign-in is replacing, in the same browser.
     *
     * <p>Signing in again overwrites the session cookie, so the previous
     * session becomes unreachable by the person who owned it — and stays valid
     * on the server until its TTL runs out. Two costs: Settings shows a list of
     * "active sessions" that grows by one every time somebody signs in on the
     * same browser and never shrinks, and a token nobody holds any more is
     * still a working credential for up to a day.
     *
     * <p>Driven by the cookie the sign-in request carried, not by matching the
     * user agent. A user agent identifies a browser build, not a browser: two
     * people on the same Chrome version share one, and revoking on that basis
     * would sign somebody else out of their own machine. The cookie is the
     * only thing that says "this exact session is being replaced", and it is
     * present precisely when that is true.
     *
     * <p>Silent when the token names nothing — an expired session, a cookie
     * from a rebuilt database, a first sign-in. None of those is an error.
     *
     * <p>// req: FR-AUTH-007, NFR-SEC-SESSION
     */
    @Transactional
    public void revokeSuperseded(String previousToken) {
        if (previousToken == null || previousToken.isBlank()) {
            return;
        }
        byte[] tokenHash = hash(previousToken);
        sessions
                .find(tokenHash)
                .ifPresent(session -> {
                    if (sessions.revoke(tokenHash) > 0) {
                        accounts
                                .findById(session.accountId())
                                .ifPresent(account -> audit.append(
                                        account.id(),
                                        account.email(),
                                        AuditAction.AUTH_SIGN_OUT,
                                        AuditAction.TARGET_SESSION,
                                        account.id(),
                                        json.createObjectNode().put("reason", "superseded")));
                    }
                });
    }

    /** Ends every session for an account, used when it is demoted or deactivated. */
    @Transactional
    public int revokeAllFor(java.util.UUID accountId) {
        return sessions.revokeAllFor(accountId);
    }

    /**
     * Removes session rows whose expiry is well past.
     *
     * <p>Retention is one TTL beyond expiry, so a row survives long enough to
     * answer "was this session already dead?" during an incident review, and no
     * longer than it has to.
     */
    @Transactional
    public int purgeExpired() {
        return sessions.deleteExpiredBefore(clock.instant().minus(properties.sessionTtl()));
    }

    // -----------------------------------------------------------------------
    // Token handling
    // -----------------------------------------------------------------------

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256 of the token, which is what the database holds.
     *
     * <p>No salt and no work factor, deliberately, and this is not the mistake it
     * resembles. A password needs both because it is low-entropy and chosen by a
     * human; this token is 256 bits from {@link SecureRandom}, so a lookup table
     * is not a threat and a slow hash would only tax the hot path of every
     * request. The hash exists so that a leaked database row is not a usable
     * credential.
     */
    static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required and absent from this JVM", e);
        }
    }

    // -----------------------------------------------------------------------
    // Password sign-in (ADR-014)
    // -----------------------------------------------------------------------

    /**
     * Exchanges an email and password for a session.
     *
     * <p><b>Every failure returns the same exception with the same message.</b>
     * Unknown address, wrong password, pending approval and rejected all look
     * identical to the caller. Telling them apart turns this endpoint into an
     * oracle for which colleagues have accounts, and — because a pending
     * registration is a real person's application — for who has applied.
     *
     * <p>The password is compared even when the account cannot sign in, and a
     * dummy comparison runs when the address is unknown. Skipping the hash on
     * the miss path makes an unknown address answer measurably faster than a
     * known one, which is the same disclosure by another route.
     *
     * <p><b>MFA (ADR-031).</b> A correct password does not always end here.
     * When the account has a confirmed TOTP enrollment, this returns {@link
     * MfaRequired} instead of issuing a session — {@code AuthController}
     * sets that as a short-lived pending-MFA cookie rather than the real
     * session cookie, and only {@code TotpService.verifyLoginChallenge}
     * (redeemed via {@link #issueForAccount}) finishes the sign-in.
     *
     * <p>// req: FR-AUTH-002, FR-AUTH-007, FR-AUTH-009, ADR-014, ADR-031
     */
    // Same reason as signIn: the refusal is audited and then thrown, and a
    // rollback would erase the record of it. Nothing else here writes.
    @Transactional(noRollbackFor = GoogleIdTokenVerifier.AuthenticationFailure.class)
    public PasswordSignInResult signInWithPassword(
            String email, String rawPassword, String userAgent, String ipAddress) {

        Optional<AccountCredential> found = accounts.findCredentialByEmailIgnoreCase(email);

        boolean passwordMatches;
        if (found.isEmpty() || found.get().passwordHash() == null) {
            // Spend the same work as a real comparison. The hash is a valid
            // bcrypt digest of a value nothing can supply.
            passwordEncoder.matches(rawPassword, DUMMY_HASH);
            passwordMatches = false;
        } else {
            passwordMatches = passwordEncoder.matches(rawPassword, found.get().passwordHash());
        }

        if (!passwordMatches || !found.get().account().canSignIn()) {
            audit.append(
                    null,
                    "unauthenticated",
                    AuditAction.AUTH_REJECTED,
                    AuditAction.TARGET_SESSION,
                    null,
                    json.createObjectNode().put("reason", "password_sign_in_refused").put("email", email));
            throw new GoogleIdTokenVerifier.AuthenticationFailure("email or password not accepted");
        }

        Account account = found.get().account();
        boolean mfaEnrolled = totps.find(account.id()).filter(AccountTotp::isConfirmed).isPresent();
        if (mfaEnrolled) {
            return mintMfaChallenge(account);
        }
        return issue(account, userAgent, ipAddress, "password");
    }

    private MfaRequired mintMfaChallenge(Account account) {
        String rawToken = newToken();
        Instant expiresAt = clock.instant().plus(MFA_CHALLENGE_TTL);
        mfaChallenges.insert(account.id(), hash(rawToken), expiresAt);
        return new MfaRequired(rawToken, expiresAt);
    }

    /**
     * Issues a real session for an account that has already cleared both
     * factors (ADR-031) — the second half of the password+TOTP sign-in,
     * called by {@code AuthController} once {@code
     * TotpService.verifyLoginChallenge} returns the account. A thin public
     * wrapper over {@link #issue}, which every other sign-in path already
     * uses privately.
     */
    @Transactional
    public IssuedSession issueForAccount(Account account, String userAgent, String ipAddress) {
        return issue(account, userAgent, ipAddress, "password-mfa");
    }

    /**
     * Records a session for an already-authenticated account.
     *
     * <p>Shared by every sign-in path deliberately (Google, password, and —
     * since ADR-031 — the second half of a password+TOTP sign-in via {@link
     * #issueForAccount}): session lifetime, hashing, the audit entry and
     * device recognition are properties of a session, not of how someone
     * proved who they were.
     */
    private IssuedSession issue(Account account, String userAgent, String ipAddress, String via) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.sessionTtl());

        // Asked before the row is written, and it has to be: a moment later
        // this session is itself a prior session from this device, and the
        // answer is no longer recoverable from the table.
        //
        // Two questions, not one. "Is this device unknown" is only meaningful
        // once some device is known — before that it is true of every device,
        // including the one in front of the person, and announcing it would
        // train them to dismiss the alert on the day it starts working.
        String deviceKey = DeviceDescription.key(userAgent);
        boolean newDevice = sessions.hasAnyKnownDevice(account.id())
                && !sessions.hasDevice(account.id(), deviceKey);

        String token = newToken();
        java.util.UUID sessionId =
                sessions.insert(hash(token), account.id(), expiresAt, userAgent, ipAddress, newDevice);

        audit.append(
                account.id(),
                account.email(),
                AuditAction.AUTH_SIGN_IN,
                AuditAction.TARGET_SESSION,
                account.id(),
                json.createObjectNode()
                        .put("role", account.role().wire())
                        .put("via", via)
                        // In the audit trail whether or not anybody was
                        // notified. A notification is a convenience that can
                        // fail; this is the record that has to survive it.
                        .put("device", DeviceDescription.describe(userAgent))
                        .put("newDevice", newDevice)
                        .put("expiresAt", expiresAt.toString()));

        // Says which of the two questions decided it, because "no alert" has
        // three innocent explanations — a device already known, an account
        // with no known device yet (the first sign-in after V16 seeds rather
        // than announces), and an alert that was raised and lost somewhere
        // later. Guessing between them has cost a day.
        log.info(
                "sign-in for account {} from \"{}\": newDevice={}",
                account.id(),
                deviceKey,
                newDevice);
        // Every sign-in, unlike the new-device alert below (ADR-089). Same
        // after-commit, off-thread contract: nothing here may slow signing in
        // or fail it, and a rolled-back session must not have told anyone
        // anything.
        events.publishEvent(new SignInEvent(account.id(), account.email(), issuedAt));

        if (newDevice) {
            // Published, not sent. The listener runs after this transaction
            // commits and off the request thread, so nobody is warned about a
            // session that was rolled back and nobody waits on Firebase to
            // finish signing in.
            events.publishEvent(new NewDeviceSignInEvent(
                    account.id(),
                    sessionId,
                    DeviceDescription.describe(userAgent),
                    ipAddress,
                    issuedAt));
        }

        // Every sign-in path funnels through here (see the class javadoc
        // above), which is exactly why this is the one place a pending
        // self-service deactivation gets cancelled rather than duplicating
        // the check into signIn/signInWithPassword/issueForAccount
        // separately (revision of ADR-042).
        boolean deactivationCancelled = cancelScheduledDeactivationOnSignIn(account);

        return new IssuedSession(token, account, expiresAt, deactivationCancelled);
    }

    // -----------------------------------------------------------------------
    // The caller's own sessions (Settings > Security)
    // -----------------------------------------------------------------------

    /** One of the caller's sessions, with the one making this request marked. */
    public record OwnSession(
            java.util.UUID id,
            String userAgent,
            String ipAddress,
            Instant issuedAt,
            Instant expiresAt,
            String device,
            boolean newDevice,
            boolean current) {}

    /**
     * Where this account is currently signed in.
     *
     * <p>Own sessions only — the account id comes from the resolved caller,
     * never from the request, so there is no parameter here to point at
     * somebody else (NFR-SEC-PII).
     *
     * <p>The current session is identified by comparing token hashes in
     * memory. The hash itself is dropped here and never reaches the response:
     * the API names a session by its opaque id instead.
     *
     * <p>// req: FR-AUTH-004, FR-AUTH-007, NFR-SEC-PII
     */
    @Transactional(readOnly = true)
    public Optional<Instant> lastSignInForAccount(java.util.UUID accountId) {
        return sessions.lastSignInForAccount(accountId);
    }

    @Transactional(readOnly = true)
    public java.util.List<OwnSession> listOwnSessions(CurrentUser caller) {
        return sessions.findLiveForAccount(caller.account().id(), clock.instant()).stream()
                .map(s -> new OwnSession(
                        s.id(),
                        s.userAgent(),
                        s.ipAddress(),
                        s.issuedAt(),
                        s.expiresAt(),
                        DeviceDescription.describe(s.userAgent()),
                        s.newDevice(),
                        MessageDigest.isEqual(s.tokenHash(), caller.sessionTokenHash())))
                .toList();
    }

    /**
     * Ends one of the caller's own sessions — "sign out this other device".
     *
     * <p>Ending the current one is allowed. It is the same thing sign-out
     * does, and refusing would mean explaining a distinction the person has no
     * reason to care about.
     *
     * <p>Returns false when the id is unknown or belongs to someone else. The
     * caller is not told which, because the difference is only useful to
     * someone enumerating other people's sessions.
     *
     * <p>// req: FR-AUTH-007, TM-003
     */
    @Transactional
    public boolean revokeOwnSession(CurrentUser caller, java.util.UUID sessionId) {
        boolean revoked = sessions.revokeOwnedById(sessionId, caller.account().id()) > 0;
        if (revoked) {
            audit.append(
                    caller.account().id(),
                    caller.account().email(),
                    AuditAction.AUTH_SIGN_OUT,
                    AuditAction.TARGET_SESSION,
                    caller.account().id(),
                    json.createObjectNode().put("via", "settings").put("session", sessionId.toString()));
        }
        return revoked;
    }

    /**
     * One sign-in or sign-out event, as the Security card shows it — no IP
     * (ADR-090): the audit row behind this never captured one.
     *
     * <p>Named {@code SignInHistoryEntry}, not {@code SignInEvent}, to stay
     * out of the way of the top-level {@link SignInEvent} (ADR-089) in this
     * same package — an unrelated, pre-existing Spring application event for
     * the mailed attendance report, not this card's read model.
     */
    public record SignInHistoryEntry(String action, Instant occurredAt, String device) {}

    /**
     * This account's own last {@code limit} sign-in/sign-out events, for the
     * Security card's "Recent sign-ins" (ADR-090). Reuses the existing audit
     * trail rather than a dedicated table — {@code device} is read back from
     * the same {@code detail} JSON {@link #issue} already writes, and is
     * blank (never null) for a sign-out row, which has no device field.
     *
     * <p>// req: FR-OPS-001, NFR-SEC-PII, ADR-090
     */
    @Transactional(readOnly = true)
    public java.util.List<SignInHistoryEntry> recentSignIns(CurrentUser caller, int limit) {
        return audit.recentSignInsForActor(caller.account().id(), limit).stream()
                .map(entry -> new SignInHistoryEntry(
                        entry.action(), entry.occurredAt(), entry.detail().path("device").asText("")))
                .toList();
    }

    /**
     * Changes the caller's own display name.
     *
     * <p>Self-service, unlike {@code AccountService.renameFor}, which is a
     * {@code super_admin} acting on someone else. Same caveat: a Google
     * sign-in refreshes the name from the provider, so this lasts until the
     * next one.
     *
     * <p>Only the name. Email identifies the account and comes from the
     * identity provider; role and status are this system's to decide. A
     * profile screen that could change any of those would be a privilege path
     * (TM-025).
     *
     * <p>// req: FR-AUTH-005, NFR-SEC-PII
     */
    @Transactional
    public Account renameSelf(CurrentUser caller, String displayName) {
        String trimmed = displayName == null ? "" : displayName.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("a display name cannot be blank");
        }
        accounts.updateDisplayName(caller.account().id(), trimmed);
        return accounts.findById(caller.account().id()).orElseThrow();
    }

    // -----------------------------------------------------------------------
    // Self-service avatar upload (ADR-026)
    // -----------------------------------------------------------------------

    /**
     * Uploads and sets the caller's own profile photo.
     *
     * <p>Self-service, the same shape as {@link #renameSelf}: acts on the
     * caller's own account, resolved from the session, never a parameter that
     * could name someone else.
     *
     * <p>The previous object is deleted only after the new one is stored — a
     * failed upload must leave the old photo in place, not a blank one.
     * {@link AvatarStorage#delete} is itself a no-op for a URL it did not
     * create, so an account whose current picture is still the identity
     * provider's is left untouched.
     *
     * <p>// req: FR-AUTH-005, NFR-SEC-PII, ADR-026
     */
    @Transactional
    public Account uploadOwnAvatar(CurrentUser caller, org.springframework.web.multipart.MultipartFile file) {
        AvatarStorage storage = avatarStorage.getIfAvailable();
        if (storage == null) {
            throw ApiException.badRequest(
                    "Photo upload is not available on this deployment yet.");
        }

        Account existing = caller.account();
        String newUrl = storage.upload(existing.id(), file);
        Instant uploadedAt = clock.instant();

        accounts.updateUploadedAvatar(existing.id(), newUrl, uploadedAt);

        if (existing.avatarUploadedAt() != null && existing.avatarUrl() != null) {
            storage.delete(existing.avatarUrl());
        }

        return accounts.findById(existing.id()).orElseThrow();
    }

    /**
     * Removes the caller's own uploaded photo, reverting to initials until
     * the next Google/Keycloak sign-in supplies the identity provider's
     * picture again — the same fallback as an account that never uploaded
     * one at all.
     *
     * <p>// req: FR-AUTH-005, NFR-SEC-PII, ADR-026
     */
    @Transactional
    public Account removeOwnAvatar(CurrentUser caller) {
        Account existing = caller.account();
        if (existing.avatarUploadedAt() == null) {
            throw ApiException.badRequest("there is no uploaded photo to remove");
        }

        AvatarStorage storage = avatarStorage.getIfAvailable();
        if (storage != null && existing.avatarUrl() != null) {
            storage.delete(existing.avatarUrl());
        }
        accounts.clearUploadedAvatar(existing.id());
        return accounts.findById(existing.id()).orElseThrow();
    }

    // -----------------------------------------------------------------------
    // Self-service deactivation, with a grace period (revision of ADR-042)
    // -----------------------------------------------------------------------

    /**
     * The only grace periods the confirm panel offers, in days. A fixed set
     * rather than free-form input: the Danger zone panel is presets, not a
     * number field, and this is what the request handler validates a caller's
     * choice against.
     */
    public static final Set<Integer> DEACTIVATION_GRACE_PERIOD_DAYS_OPTIONS = Set.of(3, 7, 14, 30);

    /**
     * Schedules the caller's own account to deactivate after {@code
     * graceDays} — Settings &rarr; Danger zone.
     *
     * <p>Deliberately its own method rather than a relaxed {@code
     * AccountService.setActive}: that one is a {@code super_admin} acting on
     * someone else's account and keeps refusing a self-target unconditionally
     * (ADR-042). This one exists to be exactly the case that refuses —
     * genuine, confirmed self-service, not a misclick, so the guard it needs
     * is different: not "never", but "never if it would lock everyone out."
     *
     * <p>Does not set {@code deactivated_at} itself. It stamps {@code
     * deactivation_scheduled_for} instead, so the account stays {@link
     * Account#isActive()} until either {@link
     * com.coc.docs.account.AccountRepository#finalizeScheduledDeactivation}
     * sweeps it once the time is reached ({@code ScheduledDeactivationSweeper})
     * or {@link #issue} cancels it first because its owner signed back in.
     *
     * <p>Every session for the account is revoked right away regardless, not
     * only the one making this call — matching {@code
     * AccountService.setActive}'s existing behaviour and the "signed out
     * everywhere now" the frontend's confirmation panel states before this is
     * ever called. Signing back in during the grace period is exactly what
     * cancels the schedule, so it has to actually require signing back in
     * (Google or password, whichever this account uses) rather than resuming
     * whatever session was live a moment ago.
     *
     * <p>// req: FR-AUTH-003, FR-AUTH-007, ADR-042
     */
    @Transactional
    public Instant deactivateSelf(CurrentUser caller, int graceDays) {
        if (!DEACTIVATION_GRACE_PERIOD_DAYS_OPTIONS.contains(graceDays)) {
            throw ApiException.badRequest("choose one of: " + DEACTIVATION_GRACE_PERIOD_DAYS_OPTIONS);
        }
        Account account = caller.account();
        if (account.role() == AppRole.SUPER_ADMIN && accounts.isLastActiveSuperAdmin(account.id())) {
            throw ApiException.conflict(
                    "you're the only active super admin; promote someone else before deactivating your account");
        }

        Instant scheduledFor = clock.instant().plus(Duration.ofDays(graceDays));
        accounts.scheduleDeactivation(account.id(), scheduledFor);
        int revoked = revokeAllFor(account.id());

        audit.append(
                account.id(),
                account.email(),
                AuditAction.ACCOUNT_DEACTIVATE_SCHEDULE,
                AuditAction.TARGET_ACCOUNT,
                account.id(),
                json.createObjectNode()
                        .put("subject", account.email())
                        .put("graceDays", graceDays)
                        .put("scheduledFor", scheduledFor.toString())
                        .put("sessionsRevoked", revoked)
                        .put("selfService", true));

        return scheduledFor;
    }

    /**
     * Cancels a pending self-service deactivation because its owner signed
     * back in before it took effect (revision of ADR-042) — called from
     * {@link #issue}, the one place every sign-in path converges, so Google,
     * password and password+TOTP sign-ins all cancel it the same way.
     *
     * <p>No-ops (and audits nothing) when nothing was pending, which is the
     * overwhelmingly common case — {@code
     * AccountRepository.cancelScheduledDeactivation}'s row count is the only
     * way to tell the two apart without an extra read.
     */
    private boolean cancelScheduledDeactivationOnSignIn(Account account) {
        if (account.deactivationScheduledFor() == null) {
            return false;
        }
        boolean cancelled = accounts.cancelScheduledDeactivation(account.id()) > 0;
        if (cancelled) {
            audit.append(
                    account.id(),
                    account.email(),
                    AuditAction.ACCOUNT_DEACTIVATE_CANCEL,
                    AuditAction.TARGET_ACCOUNT,
                    account.id(),
                    json.createObjectNode().put("subject", account.email()).put("selfService", true));
        }
        return cancelled;
    }

    /**
     * Finalizes every self-service deactivation whose grace period has
     * passed without a cancelling sign-in — {@code
     * ScheduledDeactivationSweeper}'s worklist (revision of ADR-042).
     *
     * <p>Revokes sessions again before finalizing, even though scheduling
     * already revoked every session that existed at that moment: nothing
     * should have created a new one since (a sign-in would have cancelled
     * the schedule instead, via {@link #issue}), but this is the last gate
     * before the account is truly deactivated, and it costs nothing to make
     * sure rather than assume.
     *
     * <p>One account failing must not stop the rest from finalizing — each
     * is its own transaction, not one covering the whole sweep, and a
     * failure is logged by the caller ({@code ScheduledDeactivationSweeper})
     * rather than thrown here.
     */
    public int finalizeDueScheduledDeactivations() {
        int finalized = 0;
        for (Account account : accounts.findDueForScheduledDeactivation(clock.instant())) {
            finalizeOneScheduledDeactivation(account);
            finalized++;
        }
        return finalized;
    }

    @Transactional
    void finalizeOneScheduledDeactivation(Account account) {
        accounts.finalizeScheduledDeactivation(account.id());
        int revoked = revokeAllFor(account.id());

        audit.append(
                account.id(),
                account.email(),
                AuditAction.ACCOUNT_DEACTIVATE,
                AuditAction.TARGET_ACCOUNT,
                account.id(),
                json.createObjectNode()
                        .put("subject", account.email())
                        .put("sessionsRevoked", revoked)
                        .put("selfService", true)
                        .put("afterGracePeriod", true));
    }

    // -----------------------------------------------------------------------
    // Self-service password change (revision of ADR-017)
    // -----------------------------------------------------------------------

    /**
     * Replaces the caller's own password — the first-login completion of a
     * {@code super_admin} temporary-password invite, and usable afterwards for
     * any account that holds a password credential.
     *
     * <p>Self-service, the same shape as {@link #renameSelf}: the account acted
     * on is the caller's own, resolved from the session, never a parameter that
     * could name someone else.
     *
     * <p><b>Current-password check (ADR-022, revision of ADR-020; made
     * unconditional for existing hashes by ADR-024; given a third branch by
     * ADR-030).</b> Whenever this account already holds a {@code
     * password_hash} — voluntary change and forced first-login completion
     * alike — the caller must prove {@code currentRawPassword} against it
     * first. Without this, a session alone (left open on a shared machine, or
     * lifted by an XSS the {@code httpOnly} cookie doesn't fully close off,
     * ADR-011) would be enough to lock the real owner out by silently setting
     * a new password. The forced flow re-proves the same temporary password
     * {@code /login-password} just verified moments earlier on this exact
     * session — ADR-022 originally exempted this case for that reason, but
     * ADR-024 removes the exemption on direct PM request for an old-password
     * field on {@code /create-password}.
     *
     * <p><b>No existing hash at all (ADR-030).</b> A Google-only account
     * setting its first password: there is no current password to prove and
     * no existing hash to refuse reusing, so both checks are skipped rather
     * than failing — this is not the old "Google-only accounts are refused
     * outright" behaviour, which ADR-030 replaces.
     *
     * <p>Every other live session for this account is revoked, not this one:
     * the caller is using this exact session to make the change, and ending it
     * here would contradict "then redirect to normal application" immediately
     * after success. See {@code PasswordResetService.resetPassword} for the
     * reset path, which has no current session to spare and revokes all of
     * them.
     *
     * <p>// req: FR-AUTH-002, FR-AUTH-007, ADR-017, ADR-022, ADR-024, ADR-030
     */
    @Transactional
    public void changeOwnPassword(CurrentUser caller, String currentRawPassword, String newRawPassword) {
        AccountCredential credential = accounts.findCredentialById(caller.id()).orElseThrow();
        boolean settingInitialPassword = credential.passwordHash() == null;

        if (!settingInitialPassword) {
            if (currentRawPassword == null
                    || currentRawPassword.isBlank()
                    || !passwordEncoder.matches(currentRawPassword, credential.passwordHash())) {
                // Reported plainly, not folded into a generic failure: the caller
                // already holds a live session as themselves, so there is no third
                // party here for a generic message to protect from enumeration —
                // same reasoning ADR-018 decision 3 applies to /reset-password.
                throw ApiException.badRequest("your current password is incorrect");
            }

            if (passwordEncoder.matches(newRawPassword, credential.passwordHash())) {
                throw ApiException.badRequest(
                        "choose a password different from your current one");
            }
        }

        accounts.updatePasswordHash(caller.id(), passwordEncoder.encode(newRawPassword));
        int revoked = sessions.revokeAllForExcept(caller.id(), caller.sessionTokenHash());

        audit.append(
                caller.id(),
                caller.email(),
                AuditAction.PASSWORD_CHANGE_COMPLETE,
                AuditAction.TARGET_ACCOUNT,
                caller.id(),
                json.createObjectNode()
                        .put("otherSessionsRevoked", revoked)
                        .put("kind", settingInitialPassword ? "initial-set" : "change"));
    }
}
