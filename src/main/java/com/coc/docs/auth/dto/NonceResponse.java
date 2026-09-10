package com.coc.docs.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Returned by {@code POST /api/auth/nonce}. The same value is also set as the {@code coc_oidc_nonce} cookie. */
public record NonceResponse(
        @Schema(description = "Pass this value as the `nonce` option to the sign-in call.") String nonce) {}
