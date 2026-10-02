package com.coc.docs.web;

import com.coc.docs.auth.CurrentUser;
import com.coc.docs.notify.AccountMailCredentialRepository;
import com.coc.docs.notify.GoogleMailOAuthService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own SMTP credential, so their attendance mail is sent by them
 * (ADR-063).
 *
 * <p><b>The account is always the session's own.</b> There is no id in any
 * path here, so there is nothing for a caller to point at somebody else — the
 * same property {@code /api/v1/claude-usage/me} relies on. A {@code
 * super_admin} has no route to another person's credential either, deliberately:
 * an administrator who could set one could send mail as any colleague.
 *
 * <p><b>Write-only.</b> {@link #status} answers whether one is stored and the
 * address it authenticates as; nothing returns the password. A compromised
 * session can overwrite the credential, which is bad, but cannot read it out,
 * which would be worse.
 *
 * <p>// req: FR-OPS-001, NFR-SEC-PII, NFR-SEC-CHANNEL, ADR-063
 */
@RestController
@RequestMapping("/api/v1/me/mail-credential")
public class MailCredentialController {

    /**
     * @param smtpUsername the mailbox to authenticate as — usually the
     *     caller's own address, but not assumed
     * @param appPassword a Google App Password. Sized, not pattern-matched:
     *     Google writes it in groups of four and people paste it either way,
     *     and rejecting a valid credential over a space is a worse failure
     *     than letting the relay reject a wrong one
     */
    public record SaveRequest(
            @NotBlank @Email String smtpUsername,
            @NotBlank @Size(min = 16, max = 64) String appPassword) {}

    /**
     * A Google authorization code, from the browser's consent popup (ADR-085).
     *
     * <p>Short-lived and single-use, which is why it may travel in a request
     * body at all: by the time it reaches a log it is already spent. The
     * refresh token it becomes never leaves the server.
     */
    public record ConnectGoogleRequest(@NotBlank String authorizationCode) {}

    /**
     * Never the password, never the refresh token.
     *
     * @param kind {@code "app_password"}, {@code "oauth"}, or null when none is
     *     stored. The UI says different things for each, and asking it to
     *     infer the kind from a boolean would mean inventing one.
     * @param oauthAvailable whether the OAuth route can be offered at all —
     *     false when the deployment has no Google client secret, which is a
     *     configuration fact, not a fact about this caller
     */
    public record StatusResponse(boolean configured, String smtpUsername, String kind, boolean oauthAvailable) {}

    private final AccountMailCredentialRepository credentials;
    private final GoogleMailOAuthService oauth;

    public MailCredentialController(AccountMailCredentialRepository credentials, GoogleMailOAuthService oauth) {
        this.credentials = credentials;
        this.oauth = oauth;
    }

    @Operation(
            summary = "Whether the caller has stored their own sending credential",
            description = "Never returns the password or refresh token — only whether one is stored, "
                    + "and which kind.")
    @GetMapping
    StatusResponse status(@AuthenticationPrincipal CurrentUser caller) {
        return new StatusResponse(
                credentials.exists(caller.id()),
                caller.email(),
                credentials.kindOf(caller.id()).orElse(null),
                oauth.isConfigured());
    }

    /**
     * Exchanges a Google authorization code for a Gmail grant (ADR-085).
     *
     * <p>Owner-only like every route here: the code is exchanged and stored
     * against the session's account, never an id in the request. The address
     * stored is the caller's own, because Google issued the grant to whoever
     * completed the consent screen and that is the account they signed in as.
     *
     * <p>422 rather than 500 when Google refuses: a closed popup, an expired
     * code, or a consent that granted nothing storable are all things the
     * person did, not faults. The UI asks them to try again.
     */
    @Operation(
            summary = "Connect the caller's Gmail by OAuth",
            description = "Exchanges a Google authorization code for a refresh token scoped to "
                    + "gmail.send and gmail.readonly. Replaces any stored App Password.")
    @PostMapping("/google")
    ResponseEntity<StatusResponse> connectGoogle(
            @Valid @RequestBody ConnectGoogleRequest request, @AuthenticationPrincipal CurrentUser caller) {

        if (!oauth.isConfigured()) {
            // Not the caller's fault and not retryable by them.
            return ResponseEntity.status(503).build();
        }
        return oauth.exchangeCode(request.authorizationCode())
                .map(grant -> {
                    credentials.saveOauth(caller.id(), caller.email(), grant.refreshToken(), grant.scope());
                    // Any access token cached under the previous grant is now
                    // the wrong one.
                    oauth.forget(caller.id());
                    return ResponseEntity.ok(
                            new StatusResponse(true, caller.email(), "oauth", true));
                })
                .orElseGet(() -> ResponseEntity.unprocessableEntity().build());
    }

    @Operation(
            summary = "Store or replace the caller's own sending credential",
            description = "A Google App Password, not the account password. Encrypted at rest; "
                    + "used only to send that person their own attendance mail.")
    @PutMapping
    ResponseEntity<Void> save(
            @Valid @RequestBody SaveRequest request, @AuthenticationPrincipal CurrentUser caller) {
        credentials.save(caller.id(), request.smtpUsername(), request.appPassword());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Remove the caller's own sending credential")
    @DeleteMapping
    ResponseEntity<Void> remove(@AuthenticationPrincipal CurrentUser caller) {
        credentials.delete(caller.id());
        return ResponseEntity.noContent().build();
    }
}
