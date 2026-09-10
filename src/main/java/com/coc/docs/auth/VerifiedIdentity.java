package com.coc.docs.auth;

/**
 * What the Keycloak-issued ID token proved about the caller, once every check
 * has passed. The identity itself originates from Google (Keycloak brokers it
 * upstream, per ADR-013); this class no longer names the token issuer because
 * that is exactly what changed.
 */
record VerifiedIdentity(String email, String displayName) {}
