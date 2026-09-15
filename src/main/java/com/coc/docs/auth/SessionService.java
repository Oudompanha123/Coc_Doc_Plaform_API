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
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
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

    /**
     * 32 bytes of {@link SecureRandom}, base64url-encoded. Opaque and
     * unguessable; the token carries no claims, so there is nothing in it to
     * tamper with and no signature to verify.
     */
    private static final int TOKEN_BYTES = 32;

    public static final String COOKIE_NAME = "coc_session";

    private final SessionRepository sessions;
    private final AccountRepository accounts;
    private final AuditRepository audit;
    private final GoogleIdTokenVerifier google;

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
            PasswordEncoder passwordEncoder) {
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
    }

    /** An issued session: the plaintext token, which exists only here and in the response. */
    public record IssuedSession(String token, Account account, Instant expiresAt) {}

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

        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.sessionTtl());

        String token = newToken();
        sessions.insert(hash(token), account.id(), expiresAt, userAgent, ipAddress);

        audit.append(
                account.id(),
                account.email(),
                AuditAction.AUTH_SIGN_IN,
                AuditAction.TARGET_SESSION,
                account.id(),
                json.createObjectNode()
                        .put("role", account.role().wire())
                        .put("expiresAt", expiresAt.toString()));

        return new IssuedSession(token, account, expiresAt);
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
                existing.createdAt(),
                existing.updatedAt(),
                existing.mustChangePassword());
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
     * <p>// req: FR-AUTH-002, FR-AUTH-007, ADR-014
     */
    // Same reason as signIn: the refusal is audited and then thrown, and a
    // rollback would erase the record of it. Nothing else here writes.
    @Transactional(noRollbackFor = GoogleIdTokenVerifier.AuthenticationFailure.class)
    public IssuedSession signInWithPassword(
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

        return issue(found.get().account(), userAgent, ipAddress, "password");
    }

    /**
     * Records a session for an already-authenticated account.
     *
     * <p>Shared by both sign-in paths deliberately: session lifetime, hashing
     * and the audit entry are properties of a session, not of how someone
     * proved who they were.
     */
    private IssuedSession issue(Account account, String userAgent, String ipAddress, String via) {
        Instant expiresAt = clock.instant().plus(properties.sessionTtl());
        String token = newToken();
        sessions.insert(hash(token), account.id(), expiresAt, userAgent, ipAddress);

        audit.append(
                account.id(),
                account.email(),
                AuditAction.AUTH_SIGN_IN,
                AuditAction.TARGET_SESSION,
                account.id(),
                json.createObjectNode()
                        .put("role", account.role().wire())
                        .put("via", via)
                        .put("expiresAt", expiresAt.toString()));

        return new IssuedSession(token, account, expiresAt);
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
     * unconditional by ADR-024).</b> Every caller — voluntary change and
     * forced first-login completion alike — must prove {@code
     * currentRawPassword} against the stored hash first. Without this, a
     * session alone (left open on a shared machine, or lifted by an XSS the
     * {@code httpOnly} cookie doesn't fully close off, ADR-011) would be
     * enough to lock the real owner out by silently setting a new password.
     * The forced flow re-proves the same temporary password {@code
     * /login-password} just verified moments earlier on this exact session —
     * ADR-022 originally exempted this case for that reason, but ADR-024
     * removes the exemption on direct PM request for an old-password field
     * on {@code /create-password}.
     *
     * <p>Refuses a Google-only account (no {@code password_hash} to replace)
     * and refuses reusing the current password — the one rule this endpoint
     * exists to enforce for a just-invited account, since a temporary password
     * that could be "changed" back to itself was never actually replaced.
     *
     * <p>Every other live session for this account is revoked, not this one:
     * the caller is using this exact session to make the change, and ending it
     * here would contradict "then redirect to normal application" immediately
     * after success. See {@code PasswordResetService.resetPassword} for the
     * reset path, which has no current session to spare and revokes all of
     * them.
     *
     * <p>// req: FR-AUTH-002, FR-AUTH-007, ADR-017, ADR-022, ADR-024
     */
    @Transactional
    public void changeOwnPassword(CurrentUser caller, String currentRawPassword, String newRawPassword) {
        AccountCredential credential = accounts.findCredentialById(caller.id()).orElseThrow();

        if (credential.passwordHash() == null) {
            throw ApiException.badRequest(
                    "this account has no password set; sign in with Google instead");
        }

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

        accounts.updatePasswordHash(caller.id(), passwordEncoder.encode(newRawPassword));
        int revoked = sessions.revokeAllForExcept(caller.id(), caller.sessionTokenHash());

        audit.append(
                caller.id(),
                caller.email(),
                AuditAction.PASSWORD_CHANGE_COMPLETE,
                AuditAction.TARGET_ACCOUNT,
                caller.id(),
                json.createObjectNode().put("otherSessionsRevoked", revoked));
    }
}
