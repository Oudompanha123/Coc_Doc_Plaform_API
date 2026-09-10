package com.coc.docs.auth;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountPrincipal;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.audit.AuditLogger;
import com.coc.docs.auth.dto.AuthResponse;
import com.coc.docs.auth.dto.LoginRequest;
import com.coc.docs.auth.dto.NonceResponse;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Google sign-in (brokered through Keycloak, per ADR-013), session
 * issuance/validation and sign-out.
 *
 * // req: FR-AUTH-001, FR-AUTH-002, FR-AUTH-007, NFR-SEC-AUTH, NFR-SEC-SESSION
 */
@RestController
@RequestMapping("/api/auth")
@Tag(
        name = "Authentication",
        description = "Google sign-in brokered through Keycloak (ADR-013), backend session issuance and sign-out.")
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
    private final SecureRandom random = new SecureRandom();

    public AuthController(
            KeycloakIdTokenService keycloakIdTokenService,
            AccountRepository accountRepository,
            SessionService sessionService,
            AuditLogger auditLogger,
            CookieSupport cookies,
            AppProperties appProperties) {
        this.keycloakIdTokenService = keycloakIdTokenService;
        this.accountRepository = accountRepository;
        this.sessionService = sessionService;
        this.auditLogger = auditLogger;
        this.cookies = cookies;
        this.appProperties = appProperties;
    }

    @Operation(
            summary = "Issue a one-time nonce for a sign-in attempt",
            description = "Pass the returned value as the `nonce` option to the sign-in call, then submit the "
                    + "resulting credential to POST /api/auth/login. The same value is also set as a "
                    + "short-lived httpOnly cookie, which /login reads back itself — the caller never needs to "
                    + "resend it.")
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
                    + "didn't match, no account is provisioned for this email, or the account is deactivated — "
                    + "see UC-004 E-3.")
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
            if (account.deactivated()) {
                throw new AuthenticationFailedException("deactivated");
            }

            String rawSessionToken = sessionService.issue(account.id());
            response.addHeader(
                    HttpHeaders.SET_COOKIE,
                    cookies.build(SESSION_COOKIE, rawSessionToken, appProperties.sessionTtl(), "/").toString());
            auditLogger.log("auth.login.success", account.id(), account.email(), "account", account.id(), Map.of());
            return AuthResponse.of(account);
        } catch (AuthenticationFailedException e) {
            // The specific reason is recorded here, server-side, for
            // forensics (FR-OPS-002) — and nowhere else. GlobalExceptionHandler
            // turns this exception into one fixed, generic 401 body.
            auditLogger.log(
                    "auth.login.failure", null, attemptedEmail, "account", null, Map.of("reason", e.reason()));
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
}
