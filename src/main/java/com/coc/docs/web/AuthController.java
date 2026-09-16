package com.coc.docs.web;

import com.coc.docs.account.Account;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.auth.PasswordResetService;
import com.coc.docs.auth.RegistrationChallenge;
import com.coc.docs.auth.RegistrationOtpService;
import com.coc.docs.auth.SessionService;
import com.coc.docs.auth.TotpService;
import com.coc.docs.auth.dto.ChangePasswordRequest;
import com.coc.docs.auth.dto.ChangePasswordResponse;
import com.coc.docs.auth.dto.ForgotPasswordRequest;
import com.coc.docs.auth.dto.ForgotPasswordResponse;
import com.coc.docs.auth.dto.MfaDisableRequest;
import com.coc.docs.auth.dto.MfaDisableResponse;
import com.coc.docs.auth.dto.MfaRequiredResponse;
import com.coc.docs.auth.dto.MfaSetupStartResponse;
import com.coc.docs.auth.dto.MfaSetupVerifyRequest;
import com.coc.docs.auth.dto.MfaSetupVerifyResponse;
import com.coc.docs.auth.dto.MfaStatusResponse;
import com.coc.docs.auth.dto.MfaVerifyRequest;
import com.coc.docs.auth.dto.PasswordLoginRequest;
import com.coc.docs.auth.dto.RegisterChallengeResponse;
import com.coc.docs.auth.dto.RegisterRequest;
import com.coc.docs.auth.dto.RegisterResponse;
import com.coc.docs.auth.dto.ResendRegistrationCodeRequest;
import com.coc.docs.auth.dto.ResetPasswordRequest;
import com.coc.docs.auth.dto.ResetPasswordResponse;
import com.coc.docs.auth.dto.VerifyRegistrationCodeRequest;
import com.coc.docs.config.AppProperties;
import com.coc.docs.auth.NonceService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
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
    private final PasswordEncoder passwordEncoder;
    private final NonceService nonces;
    private final PasswordResetService passwordResets;
    private final RegistrationOtpService registrationOtp;
    private final TotpService totp;

    public AuthController(
            SessionService sessions,
            AppProperties properties,
            PasswordEncoder passwordEncoder,
            NonceService nonces,
            PasswordResetService passwordResets,
            RegistrationOtpService registrationOtp,
            TotpService totp) {
        this.sessions = sessions;
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
        this.nonces = nonces;
        this.passwordResets = passwordResets;
        this.registrationOtp = registrationOtp;
        this.totp = totp;
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

        // Read before the new one is issued: the cookie this request carried
        // names the session being replaced in this same browser, and leaving it
        // live would grow the "active sessions" list by one on every sign-in
        // and leave a working credential nobody holds (SessionService.
        // revokeSuperseded).
        sessions.revokeSuperseded(readCookie(http, SessionService.COOKIE_NAME));

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
                        sessions.lastSignInForAccount(issued.account().id()).orElse(null),
                        issued.account().mustChangePassword(),
                        issued.account().hasPassword()));
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
                sessions.lastSignInForAccount(updated.id()).orElse(null),
                updated.mustChangePassword(),
                updated.hasPassword());
    }

    /**
     * Uploads the caller's own profile photo (ADR-026).
     *
     * <p>Self-service, the same floor as {@link #updateProfile}: acts on the
     * caller's own account, never one a parameter could name. Multipart
     * because this is a binary file, not a JSON field — everything else
     * about it (validation, storage, precedence over the identity provider's
     * picture) belongs to {@code SessionService.uploadOwnAvatar}.
     *
     * <p>// req: FR-AUTH-005, NFR-SEC-PII, ADR-026
     */
    @PostMapping(value = "/me/avatar", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiDtos.MeResponse uploadAvatar(
            @org.springframework.web.bind.annotation.RequestParam("file")
                    org.springframework.web.multipart.MultipartFile file,
            @AuthenticationPrincipal CurrentUser caller) {

        if (caller == null) {
            throw ApiException.forbidden("sign in first");
        }
        Account updated = sessions.uploadOwnAvatar(caller, file);
        return new ApiDtos.MeResponse(
                updated.id(),
                updated.email(),
                updated.displayName(),
                updated.role().wire(),
                updated.avatarUrl(),
                true,
                updated.createdAt(),
                sessions.lastSignInForAccount(updated.id()).orElse(null),
                updated.mustChangePassword(),
                updated.hasPassword());
    }

    /**
     * Removes the caller's own uploaded photo, reverting to the identity
     * provider's picture (or initials) — see
     * {@code SessionService.removeOwnAvatar} (ADR-026).
     *
     * <p>// req: FR-AUTH-005, NFR-SEC-PII, ADR-026
     */
    @DeleteMapping("/me/avatar")
    ApiDtos.MeResponse removeAvatar(@AuthenticationPrincipal CurrentUser caller) {
        if (caller == null) {
            throw ApiException.forbidden("sign in first");
        }
        Account updated = sessions.removeOwnAvatar(caller);
        return new ApiDtos.MeResponse(
                updated.id(),
                updated.email(),
                updated.displayName(),
                updated.role().wire(),
                updated.avatarUrl(),
                true,
                updated.createdAt(),
                sessions.lastSignInForAccount(updated.id()).orElse(null),
                updated.mustChangePassword(),
                updated.hasPassword());
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

    /**
     * The pending-MFA cookie (ADR-031) — same shape as {@link
     * #nonceCookie}, and for the same reason: it proves nothing on its own
     * (only its hash is stored, and it grants no access by itself), so it
     * carries the same {@code httpOnly}/{@code secure}/{@code sameSite=Lax}
     * flags as the real session cookie without being one.
     */
    private ResponseCookie mfaPendingCookie(SessionService.MfaRequired mfaRequired) {
        return ResponseCookie.from(TotpService.MFA_PENDING_COOKIE_NAME, mfaRequired.challengeToken())
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.between(Instant.now(), mfaRequired.expiresAt()))
                .build();
    }

    private ResponseCookie expiredMfaPendingCookie() {
        return ResponseCookie.from(TotpService.MFA_PENDING_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
    }

    /** Seconds until the pending-MFA challenge expires — for the response body, not the cookie. */
    private static long mfaChallengeTtlSeconds(SessionService.MfaRequired mfaRequired) {
        return Math.max(0, Duration.between(Instant.now(), mfaRequired.expiresAt()).toSeconds());
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
    // Password self-registration (ADR-014, ADR-017), email OTP (ADR-021)
    // -----------------------------------------------------------------------

    /**
     * Step 1 of registration: mints a 6-digit code and emails it, rather than
     * creating an account directly (ADR-021, revision of ADR-014/017).
     *
     * <p>No account row exists after this call — proving control of the
     * inbox happens before the row is created, not after. {@link
     * #verifyRegistration} is the step that actually creates it, via the
     * same {@code AccountService.register} this endpoint used to call
     * directly; that method's {@code pending}/{@code guest}, literal-role
     * behaviour (ADR-017, TM-025) is completely unchanged.
     *
     * <p>202: accepted, and there still isn't even a pending application to
     * approve until the code is confirmed.
     *
     * <p>// req: FR-AUTH-002, FR-AUTH-003, ADR-014, ADR-017, ADR-021
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    RegisterChallengeResponse register(@Valid @RequestBody RegisterRequest request) {
        RegistrationChallenge challenge = registrationOtp.start(
                request.email(), request.displayName(), passwordEncoder.encode(request.password()));
        return challengeResponse(challenge);
    }

    /**
     * Re-sends the registration code — the "I didn't get it" / "it expired"
     * path. Subject to the same 60-second cooldown as resubmitting the form
     * itself; see {@code RegistrationOtpService}.
     *
     * <p>Public, and it has to be — this runs before any account, and
     * therefore any session, exists.
     *
     * <p>// req: ADR-021
     */
    @PostMapping("/register/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    RegisterChallengeResponse resendRegistrationCode(@Valid @RequestBody ResendRegistrationCodeRequest request) {
        RegistrationChallenge challenge = registrationOtp.resend(request.email());
        return challengeResponse(challenge);
    }

    /**
     * Step 3: confirms the emailed code — the step that used to be all of
     * {@code /register} (ADR-021). Creates a {@code pending} row at role
     * {@code guest} — an application, not an account that works (ADR-017).
     *
     * <p>201, not 202: unlike step 1, a resource now genuinely exists. It is
     * still not usable — {@code pending} and {@code active} remain different
     * claims, same as they always have been.
     *
     * <p>An expired or wrong code is reported plainly rather than folded
     * into one generic failure — see {@code RegistrationOtpService.verify}'s
     * javadoc for why the anti-enumeration reasoning behind {@link
     * #signInWithPassword}'s genericism does not apply here.
     *
     * <p>// req: FR-AUTH-002, FR-AUTH-003, ADR-014, ADR-017, ADR-021
     */
    @PostMapping("/register/verify")
    @ResponseStatus(HttpStatus.CREATED)
    RegisterResponse verifyRegistration(@Valid @RequestBody VerifyRegistrationCodeRequest request) {
        Account created = registrationOtp.verify(request.email(), request.code());

        return new RegisterResponse(
                created.status().wire().toUpperCase(Locale.ROOT),
                "Your email is verified and your registration has been received. An administrator "
                        + "has to approve it before you can sign in.");
    }

    /** Shared by {@link #register} and {@link #resendRegistrationCode} — both hand back the same shape. */
    private static RegisterChallengeResponse challengeResponse(RegistrationChallenge challenge) {
        long ttlSeconds = RegistrationOtpService.TTL.toSeconds();
        return new RegisterChallengeResponse(
                "We've sent a 6-digit verification code to " + challenge.email() + ". It expires in "
                        + (ttlSeconds / 60) + " minutes.",
                ttlSeconds);
    }

    /**
     * Exchanges an email and password for a session cookie — or, since
     * ADR-031, a pending-MFA cookie when the account has confirmed TOTP.
     *
     * <p>Refusals are deliberately indistinguishable — see
     * {@code SessionService.signInWithPassword}. This method adds nothing to
     * the response that would tell them apart. The MFA branch is not a
     * refusal at all: the password was correct, and the caller is told so
     * plainly (unlike a rejected sign-in, there is no anti-enumeration
     * reason to hide it — this is the same account, mid-sign-in, being told
     * about its own second factor).
     *
     * <p>// req: FR-AUTH-002, FR-AUTH-007, FR-AUTH-009, ADR-014, ADR-031
     */
    @PostMapping("/login-password")
    ResponseEntity<?> signInWithPassword(
            @Valid @RequestBody PasswordLoginRequest request, HttpServletRequest http) {

        // Same as the Google path above: the incoming cookie names the session
        // this sign-in supersedes.
        sessions.revokeSuperseded(readCookie(http, SessionService.COOKIE_NAME));

        SessionService.PasswordSignInResult result = sessions.signInWithPassword(
                request.email(),
                request.password(),
                http.getHeader(HttpHeaders.USER_AGENT),
                clientIp(http));

        return switch (result) {
            case SessionService.IssuedSession issued -> ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, sessionCookie(issued).toString())
                    .body(new ApiDtos.MeResponse(
                            issued.account().id(),
                            issued.account().email(),
                            issued.account().displayName(),
                            issued.account().role().wire(),
                            issued.account().avatarUrl(),
                            true,
                            issued.account().createdAt(),
                            sessions.lastSignInForAccount(issued.account().id()).orElse(null),
                            issued.account().mustChangePassword(),
                            issued.account().hasPassword()));
            case SessionService.MfaRequired mfaRequired -> ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, mfaPendingCookie(mfaRequired).toString())
                    .body(new MfaRequiredResponse(true, mfaChallengeTtlSeconds(mfaRequired)));
        };
    }

    // -----------------------------------------------------------------------
    // TOTP second factor (ADR-031)
    // -----------------------------------------------------------------------

    /**
     * Completes a password sign-in that returned {@link
     * MfaRequiredResponse}: redeems the {@code coc_mfa_pending} cookie
     * against a TOTP code and, on success, issues the real session.
     *
     * <p>Public, and it has to be — no session exists until this call
     * succeeds. What stands in for authentication here is the pending-MFA
     * cookie itself, proof that this browser already supplied a correct
     * password moments earlier ({@code SessionService.signInWithPassword}).
     *
     * <p>// req: FR-AUTH-009, ADR-031
     */
    @PostMapping("/mfa/verify")
    ResponseEntity<ApiDtos.MeResponse> verifyMfa(
            @Valid @RequestBody MfaVerifyRequest request, HttpServletRequest http) {

        String pendingToken = readCookie(http, TotpService.MFA_PENDING_COOKIE_NAME);
        if (pendingToken == null) {
            throw ApiException.badRequest("This sign-in attempt has expired. Please sign in again.");
        }

        Account account = totp.verifyLoginChallenge(pendingToken, request.code());
        SessionService.IssuedSession issued = sessions.issueForAccount(
                account, http.getHeader(HttpHeaders.USER_AGENT), clientIp(http));

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie(issued).toString())
                // Spent — the same "don't leave a used one-shot cookie lying
                // around" reasoning /google already applies to the nonce.
                .header(HttpHeaders.SET_COOKIE, expiredMfaPendingCookie().toString())
                .body(new ApiDtos.MeResponse(
                        issued.account().id(),
                        issued.account().email(),
                        issued.account().displayName(),
                        issued.account().role().wire(),
                        issued.account().avatarUrl(),
                        true,
                        issued.account().createdAt(),
                        sessions.lastSignInForAccount(issued.account().id()).orElse(null),
                        issued.account().mustChangePassword(),
                        issued.account().hasPassword()));
    }

    /**
     * Starts (or restarts) TOTP enrollment for the caller's own account —
     * the QR code and manual-entry secret a setup wizard needs.
     *
     * <p>// req: FR-AUTH-009, ADR-031
     */
    @PostMapping("/mfa/setup/start")
    MfaSetupStartResponse startMfaSetup(@AuthenticationPrincipal CurrentUser caller) {
        if (caller == null) {
            throw ApiException.forbidden("sign in first");
        }
        TotpService.SetupMaterial material = totp.startSetup(caller);
        return new MfaSetupStartResponse(
                material.secret(), material.qrCodeDataUri(), material.issuer(), material.accountLabel());
    }

    /**
     * Confirms a pending enrollment with a code from the authenticator app —
     * from this point on, {@code /login-password} gates this account behind
     * it.
     *
     * <p>// req: FR-AUTH-009, ADR-031
     */
    @PostMapping("/mfa/setup/verify")
    MfaSetupVerifyResponse verifyMfaSetup(
            @Valid @RequestBody MfaSetupVerifyRequest request, @AuthenticationPrincipal CurrentUser caller) {
        if (caller == null) {
            throw ApiException.forbidden("sign in first");
        }
        totp.confirmSetup(caller, request.code());
        return new MfaSetupVerifyResponse("Two-factor authentication is now on for your account.");
    }

    /**
     * Turns TOTP off — requires the current password (see {@code
     * TotpService.disable}'s Javadoc for why).
     *
     * <p>// req: FR-AUTH-009, ADR-031
     */
    @PostMapping("/mfa/disable")
    MfaDisableResponse disableMfa(
            @Valid @RequestBody MfaDisableRequest request, @AuthenticationPrincipal CurrentUser caller) {
        if (caller == null) {
            throw ApiException.forbidden("sign in first");
        }
        totp.disable(caller, request.currentPassword());
        return new MfaDisableResponse("Two-factor authentication has been turned off.");
    }

    /**
     * Whether the caller's own account currently has TOTP enrolled — what
     * {@code SecurityTab}'s toggle reflects on load.
     *
     * <p>// req: FR-AUTH-009, ADR-031
     */
    @GetMapping("/mfa/status")
    MfaStatusResponse mfaStatus(@AuthenticationPrincipal CurrentUser caller) {
        if (caller == null) {
            throw ApiException.forbidden("sign in first");
        }
        return new MfaStatusResponse(totp.isEnabledFor(caller.id()));
    }

    // -----------------------------------------------------------------------
    // Self-service password change (revision of ADR-017)
    // -----------------------------------------------------------------------

    /**
     * Replaces the caller's own password, or gives them one for the first time.
     *
     * <p>Three callers share this one route: the "Create New Password" step
     * of the first-login flow — the caller already holds a session, issued
     * by {@link #signInWithPassword} on the temporary password — the
     * voluntary "My Profile &rarr; Change Password" self-service screen
     * (ADR-022), and, since ADR-030, that same screen used by a Google-only
     * account to set a password it never had. {@code
     * SessionService.changeOwnPassword} decides which of those three this
     * is and verifies {@code currentPassword} for the two that have an
     * existing hash to prove — since ADR-024, the forced flow also re-proves
     * the temporary password it signed in with rather than being exempt; see
     * that method's javadoc for why. {@code caller.account().mustChangePassword()}
     * and {@code caller.account().hasPassword()}, both read here before the
     * call, are what tell the three apart to pick the confirmation message
     * below.
     *
     * <p>Requires only {@code USER} rank because it acts on the caller's own
     * account and cannot name another (see {@code SecurityConfig}'s
     * self-service block). Both new-password fields are required here even
     * though the frontend already checks they match — see {@code
     * ChangePasswordRequest}'s javadoc.
     *
     * <p>The confirmation message differs by which entry point this was: the
     * forced flow says the account is now usable, and the initial-set case
     * says a password now exists at all — either would be a false or
     * confusing promise for the other two callers.
     *
     * <p>// req: FR-AUTH-002, FR-AUTH-007, ADR-017, ADR-022, ADR-024, ADR-030
     */
    @PostMapping("/change-password")
    ChangePasswordResponse changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal CurrentUser caller) {

        if (caller == null) {
            throw ApiException.forbidden("sign in first");
        }
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw ApiException.badRequest("newPassword and confirmPassword must match");
        }

        boolean wasForcedFirstLogin = caller.account().mustChangePassword();
        // Read before the call: changeOwnPassword is what makes this true,
        // so it has to be captured from the state at the start of the
        // request, same as wasForcedFirstLogin above (ADR-030).
        boolean hadNoPassword = !caller.account().hasPassword();
        sessions.changeOwnPassword(caller, request.currentPassword(), request.newPassword());

        String message;
        if (hadNoPassword) {
            message = "Your password has been set. You can now also sign in with your email and password.";
        } else if (wasForcedFirstLogin) {
            message = "Password created successfully. You can now access your account.";
        } else {
            message = "Your password has been changed.";
        }
        return new ChangePasswordResponse(message);
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
     * would tell the remaining cases apart; a Google-only account (getting
     * its first password) and a genuinely emailed reset are indistinguishable
     * from here (ADR-018 decision 3, narrowed by ADR-019) — and, since
     * ADR-029, both really do mint a token and send an email now, not just
     * share an API response.
     *
     * <p>Public, and it has to be — a signed-out visitor who forgot their
     * password is, by definition, not signed in yet.
     *
     * <p>// req: FR-AUTH-002, ADR-018, ADR-019, ADR-029
     */
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.OK)
    ForgotPasswordResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordResets.requestReset(request.email());
        return ForgotPasswordResponse.generic();
    }

    /**
     * Completes a password reset — or, for a Google-only account, a first
     * password (ADR-029): sets the new password and spends the token.
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
     * <p>// req: FR-AUTH-002, FR-AUTH-007, ADR-018, ADR-029
     */
    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.OK)
    ResetPasswordResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        String message = passwordResets.resetPassword(request.token(), request.newPassword());
        return new ResetPasswordResponse(message);
    }
}
