package com.coc.docs.web;

import com.coc.docs.account.Account;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.account.AccountService;
import com.coc.docs.auth.SessionService;
import com.coc.docs.auth.dto.PasswordLoginRequest;
import com.coc.docs.auth.dto.RegisterRequest;
import com.coc.docs.auth.dto.RegisterResponse;
import com.coc.docs.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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

    public AuthController(
            SessionService sessions,
            AppProperties properties,
            AccountService accounts,
            PasswordEncoder passwordEncoder) {
        this.sessions = sessions;
        this.properties = properties;
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Exchanges a verified Google ID token for a session cookie.
     *
     * <p>// req: FR-AUTH-001, FR-AUTH-002
     */
    @PostMapping("/google")
    ResponseEntity<ApiDtos.MeResponse> signIn(
            @Valid @RequestBody ApiDtos.GoogleSignInRequest request, HttpServletRequest http) {

        SessionService.IssuedSession issued =
                sessions.signIn(request.idToken(), http.getHeader(HttpHeaders.USER_AGENT), clientIp(http));

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie(issued).toString())
                .body(new ApiDtos.MeResponse(
                        issued.account().id(),
                        issued.account().email(),
                        issued.account().displayName(),
                        issued.account().role().wire(),
                        true));
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
        return caller == null ? ApiDtos.MeResponse.anonymous() : ApiDtos.MeResponse.of(caller);
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
     * <p>Creates an account that can sign in immediately: the row is
     * {@code active} with role {@code user}. Role and status are literals in
     * the insert, never taken from this request — that is what keeps
     * registration off the privilege-escalation path (TM-025).
     *
     * <p>As of 2026-09-11, product direction removed the {@code super_admin}
     * approval gate ADR-014 originally put here — see
     * {@code AccountService.register}'s javadoc and ADR-014's "Alternatives
     * Considered" table, which explicitly rejected this for the security
     * reason it reopens. Recorded here pending that ADR's own revision.
     *
     * <p>200, not 202: the account is usable as soon as this call returns, so
     * there is nothing left "accepted but pending".
     *
     * <p>// req: FR-AUTH-002, FR-AUTH-003, ADR-014
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.OK)
    RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        Account created = accounts.register(
                request.email(), request.displayName(), passwordEncoder.encode(request.password()));

        return new RegisterResponse(
                created.status().wire().toUpperCase(Locale.ROOT),
                "Your account has been created. You can now log in.");
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
                        true));
    }
}
