package com.coc.docs.web;

import com.coc.docs.auth.CurrentUser;
import com.coc.docs.notify.AccountMailCredentialRepository;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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

    /** Never the password. */
    public record StatusResponse(boolean configured, String smtpUsername) {}

    private final AccountMailCredentialRepository credentials;

    public MailCredentialController(AccountMailCredentialRepository credentials) {
        this.credentials = credentials;
    }

    @Operation(
            summary = "Whether the caller has stored their own sending credential",
            description = "Never returns the password itself — only whether one is stored.")
    @GetMapping
    StatusResponse status(@AuthenticationPrincipal CurrentUser caller) {
        return new StatusResponse(credentials.exists(caller.id()), caller.email());
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
