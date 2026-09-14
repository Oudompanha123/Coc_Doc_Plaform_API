package com.coc.docs.web;

import com.coc.docs.account.Account;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.account.AccountService;
import com.coc.docs.auth.PasswordResetService;
import com.coc.docs.auth.SessionService;
import com.coc.docs.auth.dto.ForgotPasswordRequest;
import com.coc.docs.auth.dto.ForgotPasswordResponse;
import com.coc.docs.auth.dto.PasswordLoginRequest;
import com.coc.docs.auth.dto.RegisterRequest;
import com.coc.docs.auth.dto.RegisterResponse;
import com.coc.docs.auth.dto.ResetPasswordRequest;
import com.coc.docs.auth.dto.ResetPasswordResponse;
import com.coc.docs.config.AppProperties;
import com.coc.docs.auth.NonceService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-in, sign-out, and "who am I".
 *
 * <p>The session token is only ever delivered as a cookie, never in a response
 * body. A body would put the credential somewhere JavaScript can read it, and
 * {@code httpOnly} is the control that closes the XSS-to-session-theft path
 * (ADR-011) — returning the token as well would hand back what the flag exists
 * to withhold.
 *
 * <p>// req: FR-AUTH-001, FR-AUTH-004, FR-AUTH-007, NFR-SEC-SESSION, ADR-011
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final SessionService sessions;
    private final AppProperties properties;
    private final AccountService accounts;
    private final PasswordEncoder passwordEncoder;
    private final NonceService nonces;
    private final PasswordResetService passwordResets;

    public AuthController(
            SessionService sessions,
            AppProperties properties,
            AccountService accounts,
            PasswordEncoder passwordEncoder,
            NonceService nonces,
            PasswordResetService passwordResets) {
        this.sessions = sessions;
        this.properties = properties;
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.nonces = nonces;
        this.passwordResets = passwordResets;
    }

    /**
     * Starts a Google sign-in attempt.
     *
     * <p>Returns a one-time nonce and sets the same value as a short-lived
     * {@code httpOnly} cookie. The caller passes the returned value to
     * Keycloak; the backend later compares the ID token's {@code nonce} claim
     * against the <em>cookie</em>, never against anything the request body
     * carries. {@link NonceService} explains why that distinction is the whole
     * control rather than a detail of it (TM-001).
     *
     * <p>Public, and it has to be — it is the first call of a sign-in, made
     * before anyone is signed in. It discloses nothing and creates nothing.
     *
     * <p>// req: FR-AUTH-001, NFR-SEC-AUTH, TM-001, ADR-013
     */
    @PostMapping("/nonce")
    ResponseEntity<ApiDtos.NonceResponse> nonce() {
        String value = nonces.newNonce();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, nonceCookie(value).toString())
                .body(new ApiDtos.NonceResponse(value));
    }

    /**
     * Exchanges a verified ID token for a session cookie — and, for someone
     * arriving for the first time, creates their account at role {@code user}.
     *
     * <p>That is what "register with Google" is here: there is no separate
     * registration endpoint, because a second place deciding what a new
     * account may do is a second place to get it wrong. See
     * {@code SessionService.createAccount}.
     *
     * <p>// req: FR-AUTH-001, FR-AUTH-002, FR-AUTH-003
     */
    @PostMapping("/google")
    ResponseEntity<ApiDtos.MeResponse> signIn(
            @Valid @RequestBody ApiDtos.GoogleSignInRequest request, HttpServletRequest http) {

        SessionService.IssuedSession issued = sessions.signIn(
                request.idToken(),
                readCookie(http, NonceService.COOKIE_NAME),
                http.getHeader(HttpHeaders.USER_AGENT),
                clientIp(http));

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie(issued).toString())
                // Spent. Leaving it in place would let a captured ID token be
                // replayed for the rest of the nonce's five minutes, which is
                // most of what the nonce exists to prevent.
                .header(HttpHeaders.SET_COOKIE, expiredNonceCookie().toString())
                .body(new ApiDtos.MeResponse(
                        issued.account().id(),
                        issued.account().email(),
                        issued.account().displayName(),
                        issued.account().role().wire(),
                        issued.account().avatarUrl(),
                        true,
                        issued.account().createdAt(),
                        sessions.lastSignInForAccount(issued.account().id()).orElse(null)));
    }

    /**
     * Reports the caller, or that there is none.
     *
     * <p>200 with {@code authenticated: false} rather than 401. This is the
     * question the frontend asks on every page load to decide whether to show a
     * sign-in button, and an unauthenticated visitor is the expected answer, not
     * an error condition.
     */
    @GetMapping("/me")
    ApiDtos.MeResponse me(@AuthenticationPrincipal CurrentUser caller) {
        if (caller == null) return ApiDtos.MeResponse.anonymous();
        return ApiDtos.MeResponse.of(caller,
                sessions.lastSignInForAccount(caller.id()).orElse(null));
    }

    /**
     * Changes the caller's own display name.
     *
     * <p>Self-service: the account is the one that made the request, never one
     * named in it. A profile screen that could name an account would be an
     * administration screen with no floor under it.
     *
     * <p>Name only. Email identifies the account and comes from the identity
     * provider; role and status are the system's to decide (TM-025).
     *
     * <p>// req: FR-AUTH-005, NFR-SEC-PII
     */
    @PatchMapping("/me")
    ApiDtos.MeResponse updateProfile(
            @Valid @RequestBody ApiDtos.UpdateProfileRequest request,
            @AuthenticationPrincipal CurrentUser caller) {

        if (caller == null) {
            throw ApiException.forbidden("sign in first");
        }
        Account updated = sessions.renameSelf(caller, request.displayName());
        return new ApiDtos.MeResponse(
                updated.id(),
                updated.email(),
                updated.displayName(),
                updated.role().wire(),
                updated.avatarUrl(),
                true,
                updated.createdAt(),
                sessions.lastSignInForAccount(updated.id()).orElse(null));
    }

    /**
     * Where this account is signed in.
     *
     * <p>The caller's own sessions and no others: the account comes from the
     * resolved session, so there is no parameter here that could point at
     * anyone else (NFR-SEC-PII).
     *
     * <p>// req: FR-AUTH-004, FR-AUTH-007
     */
    @GetMapping("/sessions")
    java.util.List<ApiDtos.SessionResponse> sessions(@AuthenticationPrincipal CurrentUser caller) {
        if (caller == null) {
            throw ApiException.forbidden("sign in first");
        }
        return sessions.listOwnSessions(caller).stream().map(ApiDtos.SessionResponse::of).toList();
    }

    /**
     * Ends one of the caller's own sessions — "sign out this other device".
     *
     * <p>204 whether or not a row was revoked. An id that belongs to someone
     * else and an id that does not exist answer identically, because telling
     * them apart is only useful to someone enumerating other people's
     * sessions.
     *
     * <p>// req: FR-AUTH-007, TM-003
     */
    @DeleteMapping("/sessions/{sessionId}")
    ResponseEntity<Void> revokeSession(
            @PathVariable java.util.UUID sessionId, @AuthenticationPrincipal CurrentUser caller) {

        if (caller == null) {
            throw ApiException.forbidden("sign in first");
        }
        sessions.revokeOwnSession(caller, sessionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Ends the session server-side and clears the cookie.
     *
     * <p>Idempotent, and permitted without a session on purpose: a caller whose
     * session has already expired still needs the cookie cleared, and answering
     * 401 here would leave a dead cookie in the browser.
     *
     * <p>// req: FR-AUTH-007
     */
    @PostMapping("/logout")
    ResponseEntity<Void> signOut(@AuthenticationPrincipal CurrentUser caller) {
        if (caller != null) {
            sessions.signOut(caller);
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expiredCookie().toString())
                .build();
    }

    /**
     * The session cookie.
     *
     * <ul>
     *   <li>{@code httpOnly} — not readable by script, so an XSS does not become
     *       a stolen session
     *   <li>{@code secure} — TLS only, configurable off for local http, where a
     *       browser would otherwise discard it and sign-in would appear to work
     *       and then not
     *   <li>{@code sameSite=Lax} — blocks cross-site submission while still
     *       allowing the top-level navigation the OIDC redirect needs
     *   <li>{@code maxAge} — matched to the server-side expiry, so the cookie and
     *       the {@code app_session} row die together
     * </ul>
     *
     * <p>// req: NFR-SEC-SESSION, ADR-011, TM-002
     */
    private ResponseCookie sessionCookie(SessionService.IssuedSession issued) {
        return ResponseCookie.from(SessionService.COOKIE_NAME, issued.token())
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(properties.sessionTtl())
                .build();
    }

    private ResponseCookie expiredCookie() {
        return ResponseCookie.from(SessionService.COOKIE_NAME, "")
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
    }

    /**
     * The nonce cookie.
     *
     * <p>{@code httpOnly} for the reason the whole mechanism depends on: if
     * script could read it, an XSS could read the expected value and mint a
     * matching token request, and the nonce would be checking the caller
     * against a value the caller supplied.
     *
     * <p>{@code path=/} rather than scoped to the auth routes. The browser
     * must send it on the sign-in POST, and a narrower path is one of those
     * settings that works until someone moves an endpoint.
     */
    private ResponseCookie nonceCookie(String value) {
        return ResponseCookie.from(NonceService.COOKIE_NAME, value)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(NonceService.TTL)
                .build();
    }

    private ResponseCookie expiredNonceCookie() {
        return ResponseCookie.from(NonceService.COOKIE_NAME, "")
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
    }

    /** Null when absent or blank, which {@code KeycloakIdTokenService} refuses outright. */
    private static String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                String value = cookie.getValue();
                return (value == null || value.isBlank()) ? null : value;
            }
        }
        return null;
    }

    /**
     * The client address, for the audit trail only.
     *
     * <p>{@code X-Forwarded-For} is client-controlled and is read here anyway,
     * because behind a load balancer it is the only way to see the real address.
     * That is acceptable precisely because this value authorises nothing — it is
     * recorded, never compared. Using it in an access decision would be trusting
     * a header, which is the mistake this codebase avoids everywhere else.
     *
     * <p>// req: FR-OPS-002
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }

    // -----------------------------------------------------------------------
    // Password self-registration (ADR-014)
    // -----------------------------------------------------------------------

    /**
     * Applies for an account.
     *
     * <p>Creates a {@code pending} row at role {@code guest} — an application,
     * not an account that works (ADR-017). Role and status are literals in
     * the insert, never taken from this request — that is what keeps
     * registration off the privilege-escalation path (TM-025).
     *
     * <p>Between 2026-09-11 and ADR-017 this produced an active account
     * immediately. Reversed, because gating Google sign-in while leaving this
     * open would put a door beside the gate: anyone could self-issue a working
     * account by choosing a password instead.
     *
     * <p>202, not 200. The request was accepted and nothing is usable yet —
     * saying 200 is how the old message came to promise a sign-in that would
     * be refused.
     *
     * <p>// req: FR-AUTH-002, FR-AUTH-003, ADR-014, ADR-017
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        Account created = accounts.register(
                request.email(), request.displayName(), passwordEncoder.encode(request.password()));

        return new RegisterResponse(
                created.status().wire().toUpperCase(Locale.ROOT),
                "Your registration has been received. An administrator has to approve it "
                        + "before you can sign in.");
    }

    /**
     * Exchanges an email and password for a session cookie.
     *
     * <p>Refusals are deliberately indistinguishable — see
     * {@code SessionService.signInWithPassword}. This method adds nothing to
     * the response that would tell them apart.
     *
     * <p>// req: FR-AUTH-002, FR-AUTH-007, ADR-014
     */
    @PostMapping("/login-password")
    ResponseEntity<ApiDtos.MeResponse> signInWithPassword(
            @Valid @RequestBody PasswordLoginRequest request, HttpServletRequest http) {

        SessionService.IssuedSession issued = sessions.signInWithPassword(
                request.email(),
                request.password(),
                http.getHeader(HttpHeaders.USER_AGENT),
                clientIp(http));

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie(issued).toString())
                .body(new ApiDtos.MeResponse(
                        issued.account().id(),
                        issued.account().email(),
                        issued.account().displayName(),
                        issued.account().role().wire(),
                        issued.account().avatarUrl(),
                        true,
                        issued.account().createdAt(),
                        sessions.lastSignInForAccount(issued.account().id()).orElse(null)));
    }

    // -----------------------------------------------------------------------
    // Password reset (ADR-018, revision of ADR-014)
    // -----------------------------------------------------------------------

    /**
     * Starts a password reset.
     *
     * <p>200 with the same generic message for every case <em>except</em> "no
     * account at all" — {@code PasswordResetService.requestReset} throws for
     * that one (ADR-019), which {@code ApiExceptionHandler} turns into the
     * {@code 404} the caller actually sees. This method adds nothing that
     * would tell the remaining cases apart; a Google-only account and a
     * genuinely emailed reset are still indistinguishable from here (ADR-018
     * decision 3, narrowed by ADR-019).
     *
     * <p>Public, and it has to be — a signed-out visitor who forgot their
     * password is, by definition, not signed in yet.
     *
     * <p>// req: FR-AUTH-002, ADR-018, ADR-019
     */
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.OK)
    ForgotPasswordResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordResets.requestReset(request.email());
        return ForgotPasswordResponse.generic();
    }

    /**
     * Completes a password reset: sets the new password and spends the token.
     *
     * <p>Unlike {@link #forgotPassword}, a rejected token here is reported
     * plainly — the caller already holds a value that came from their own
     * mailbox, so refusing it discloses nothing about anyone else's account
     * (ADR-018 decision 3; see {@code PasswordResetService.resetPassword}).
     *
     * <p>Never sets a session cookie, matching {@code /register}'s "this
     * never signs the caller in" convention — and every session that
     * predated the reset is revoked server-side, so the caller signs in fresh
     * at {@code /login} either way.
     *
     * <p>// req: FR-AUTH-002, FR-AUTH-007, ADR-018
     */
    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.OK)
    ResetPasswordResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResets.resetPassword(request.token(), request.newPassword());
        return new ResetPasswordResponse("Your password has been reset. You can now log in.");
    }
}
