package com.coc.docs.auth;

/**
 * What the Keycloak-issued ID token proved about the caller, once every check
 * has passed. The identity itself originates from Google (Keycloak brokers it
 * upstream, per ADR-013); this class no longer names the token issuer because
 * that is exactly what changed.
 *
 * @param avatarUrl the {@code picture} claim, or null. Null is ordinary: the
 *     claim is optional, the direct-Google fallback does not request it, and
 *     a password-registered account has no provider to supply one. An absent
 *     picture is normal, never a failure.
 */
record VerifiedIdentity(String email, String displayName, String avatarUrl) {}
