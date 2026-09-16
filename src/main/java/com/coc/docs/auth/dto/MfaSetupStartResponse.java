package com.coc.docs.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Returned by {@code POST /api/v1/auth/mfa/setup/start} (ADR-028): the raw
 * material to scan or type into an authenticator app. Not returned again
 * once {@code POST /api/v1/auth/mfa/setup/verify} confirms the enrollment —
 * see {@code TotpService.startSetup}'s Javadoc for why the secret isn't
 * exposed unnecessarily after that point.
 */
public record MfaSetupStartResponse(
        @Schema(description = "The base32 secret, for manual entry if scanning the QR code isn't possible.")
                String secret,
        @Schema(description = "A data: URI PNG — render directly in an <img> tag, no separate fetch needed.")
                String qrCodeDataUri,
        @Schema(description = "The issuer name shown inside the authenticator app.") String issuer,
        @Schema(description = "The account label (email) shown alongside the issuer inside the authenticator app.")
                String accountLabel) {}
