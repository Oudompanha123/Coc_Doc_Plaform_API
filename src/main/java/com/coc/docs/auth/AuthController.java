package com.coc.docs.auth;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountCredential;
import com.coc.docs.account.AccountPrincipal;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.audit.AuditLogger;
import com.coc.docs.auth.dto.AuthResponse;
import com.coc.docs.auth.dto.LoginRequest;
import com.coc.docs.auth.dto.NonceResponse;
import com.coc.docs.auth.dto.PasswordLoginRequest;
import com.coc.docs.auth.dto.RegisterRequest;
import com.coc.docs.auth.dto.RegisterResponse;
import com.coc.docs.config.AppProperties;
import com.coc.docs.security.CookieSupport;
import com.coc.docs.security.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Google sign-in (brokered through Keycloak, per ADR-013), password
 * self-registration and sign-in (ADR-014), session issuance/validation and
 * sign-out.
 *
 * // req: FR-AUTH-001, FR-AUTH-002, FR-AUTH-003, FR-AUTH-007, NFR-SEC-AUTH, NFR-SEC-SESSION
 */
@RestController
@RequestMapping("/api/auth")
@Tag(
        name = "Authentication",
        description = "Google sign-in brokered through Keycloak (ADR-013), password self-registration and "
                + "sign-in (ADR-014), backend session issuance and sign-out.")
public class AuthController {

    private static final String NONCE_COOKIE = "coc_oidc_nonce";
    private static final String SESSION_COOKIE = "coc_session";
    private static final String AUTH_COOKIE_PATH = "/api/auth";
    private static final int NONCE_BYTES = 24;

    private final KeycloakIdTokenService keycloakIdTokenService;
    private final AccountRepository accountRepository;
    private final SessionService sessionService;
    private final AuditLogger auditLogger;
    private final CookieSupport cookies;
    private final AppProperties appProperties;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    public AuthController(
            KeycloakIdTokenService keycloakIdTokenService,
            AccountRepository accountRepository,
            SessionService sessionService,
            AuditLogger auditLogger,
            CookieSupport cookies,
            AppProperties appProperties,
            PasswordEncoder passwordEncoder) {
        this.keycloakIdTokenService = keycloakIdTokenService;
        this.accountRepository = accountRepository;
        this.sessionService = sessionService;
        this.auditLogger = auditLogger;
        this.cookies = cookies;
        this.appProperties = appProperties;
        this.passwordEncoder = passwordEncoder;
    }

    @Operation(
            summary = "Issue a one-time nonce for a sign-in attempt",
            description = "Pass the returned value as the `nonce` option to the sign-in call, then submit the "
                    + "resulting credential to POST /api/auth/login. The same value is also set as a "
                    + "short-lived httpOnly cookie, which /login reads back itself — the caller never needs to "
                    + "resend it. Not used by the password sign-in path (POST /api/auth/login-password).")
    @ApiResponse(responseCode = "200", description = "Nonce issued")
    @PostMapping("/nonce")
    public NonceResponse nonce(HttpServletResponse response) {
        byte[] bytes = new byte[NONCE_BYTES];
        random.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                cookies.build(NONCE_COOKIE, value, appProperties.oidc().nonceTtl(), AUTH_COOKIE_PATH).toString());
        return new NonceResponse(value);
    }

    @Operation(
            summary = "Verify a Keycloak-issued ID token and issue a backend session",
            description = "Signature, issuer, audience and nonce are checked server-side against the configured "
                    + "Keycloak realm's published JWK set before anything here is believed. Keycloak brokers "
                    + "Google upstream (ADR-013); this endpoint never trusts Google's JWK set directly, and "
                    + "roles are never read from the token — only from the account table. // req: TM-001, TM-003")
    @ApiResponse(responseCode = "200", description = "Signed in; the coc_session cookie is now set")
    @ApiResponse(
            responseCode = "401",
            description = "Sign-in refused. Deliberately identical whether the token was invalid, the nonce "
                    + "didn't match, no account is provisioned for this email, or the account is deactivated, "
                    + "pending review, or was rejected — see UC-004 E-3.")
    @PostMapping("/login")
    public AuthResponse login(
            @Valid @RequestBody LoginRequest request,
            @CookieValue(name = NONCE_COOKIE, required = false) String nonceCookie,
            HttpServletResponse response) {
        // The nonce is one-time-use regardless of outcome: clear it now.
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.clear(NONCE_COOKIE, AUTH_COOKIE_PATH).toString());

        String attemptedEmail = "";
        try {
            VerifiedIdentity identity = keycloakIdTokenService.verify(request.idToken(), nonceCookie);
            attemptedEmail = identity.email();

            // No auto-provisioning (UC-004 E-1): a valid identity with no
            // matching account grants nothing.
            Account account = accountRepository
                    .findByEmailIgnoreCase(identity.email())
                    .orElseThrow(() -> new AuthenticationFailedException("no_account"));
            return completeSignIn(account, response);
        } catch (AuthenticationFailedException e) {
            // The specific reason is recorded here, server-side, for
            // forensics (FR-OPS-002) — and nowhere else. GlobalExceptionHandler
            // turns this exception into one fixed, generic 401 body.
            auditLogger.log(
                    "auth.login.failure", null, attemptedEmail, "account", null, Map.of("reason", e.reason()));
            throw e;
        }
    }

    @Operation(
            summary = "Register a new account with a password (ADR-014)",
            description = "Creates an account with status PENDING and role user. The account cannot sign in — by "
                    + "either path — until a super_admin approves it (POST /api/admin/accounts/{id}/approve). No "
                    + "session cookie is set by this endpoint. // req: FR-AUTH-002, FR-AUTH-003")
    @ApiResponse(responseCode = "201", description = "Registration submitted, awaiting super_admin review")
    @ApiResponse(responseCode = "409", description = "This email is already registered")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        if (accountRepository.findByEmailIgnoreCase(request.email()).isPresent()) {
            throw new EmailAlreadyRegisteredException();
        }
        String passwordHash = passwordEncoder.encode(request.password());
        Account account;
        try {
            account = accountRepository.insertPendingRegistration(
                    request.email(), request.displayName(), passwordHash);
        } catch (DuplicateKeyException e) {
            // A concurrent registration for the same email won the race
            // between the check above and this insert — same outcome.
            throw new EmailAlreadyRegisteredException();
        }
        auditLogger.log("auth.register", account.id(), account.email(), "account", account.id(), Map.of());
        return new RegisterResponse(
                "PENDING", "Registration submitted. An administrator will review your account before you can sign in.");
    }

    @Operation(
            summary = "Sign in with a password (ADR-014)",
            description = "Only succeeds for an approved (status ACTIVE), non-deactivated, self-registered "
                    + "account. Same generic 401 as POST /api/auth/login for every refusal reason — a wrong "
                    + "password and \"this account has no password set at all\" (Google/Keycloak-only accounts) "
                    + "are deliberately indistinguishable, per UC-004 E-3.")
    @ApiResponse(responseCode = "200", description = "Signed in; the coc_session cookie is now set")
    @ApiResponse(responseCode = "401", description = "Sign-in refused")
    @PostMapping("/login-password")
    public AuthResponse loginWithPassword(@Valid @RequestBody PasswordLoginRequest request, HttpServletResponse response) {
        try {
            AccountCredential credential = accountRepository
                    .findCredentialByEmailIgnoreCase(request.email())
                    .orElseThrow(() -> new AuthenticationFailedException("no_account"));
            if (credential.passwordHash() == null
                    || !passwordEncoder.matches(request.password(), credential.passwordHash())) {
                throw new AuthenticationFailedException("bad_credentials");
            }
            return completeSignIn(credential.account(), response);
        } catch (AuthenticationFailedException e) {
            auditLogger.log(
                    "auth.login.failure", null, request.email(), "account", null, Map.of("reason", e.reason()));
            throw e;
        }
    }

    @Operation(summary = "Sign out, invalidating the session server-side")
    @ApiResponse(responseCode = "204", description = "Signed out; the old cookie no longer validates")
    @SecurityRequirement(name = "cookieAuth")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionCookie,
            @AuthenticationPrincipal AccountPrincipal principal,
            HttpServletResponse response) {
        // A real server-side revocation (FR-AUTH-007): the row backing this
        // cookie is deleted, not merely cleared client-side (ADR-011 §2).
        sessionService.invalidate(sessionCookie);
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.clear(SESSION_COOKIE, "/").toString());
        auditLogger.log("auth.logout", principal.id(), principal.email(), "account", principal.id(), Map.of());
    }

    @Operation(summary = "The signed-in caller's own account")
    @ApiResponse(responseCode = "200", description = "The current session's account")
    @SecurityRequirement(name = "cookieAuth")
    @GetMapping("/me")
    public AuthResponse me(@AuthenticationPrincipal AccountPrincipal principal) {
        return AuthResponse.of(principal);
    }

    /**
     * Shared by both sign-in paths once an {@link Account} has been matched
     * by whatever means (a Keycloak-verified identity, or a password): the
     * pending/rejected/deactivated check, session issuance, and success
     * audit log are identical either way — one session model regardless of
     * how identity was proven (ADR-013's principle, extended by ADR-014 to
     * a second identity-proving method).
     */
    private AuthResponse completeSignIn(Account account, HttpServletResponse response) {
        if (!account.canSignIn()) {
            throw new AuthenticationFailedException(switch (account.status()) {
                case PENDING -> "pending_approval";
                case REJECTED -> "rejected";
                // status is ACTIVE but canSignIn() is false: deactivated.
                case ACTIVE -> "deactivated";
            });
        }
        String rawSessionToken = sessionService.issue(account.id());
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                cookies.build(SESSION_COOKIE, rawSessionToken, appProperties.sessionTtl(), "/").toString());
        auditLogger.log("auth.login.success", account.id(), account.email(), "account", account.id(), Map.of());
        return AuthResponse.of(account);
    }
}
