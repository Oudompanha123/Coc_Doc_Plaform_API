package com.coc.docs.account;

/**
 * An {@link Account} together with its password hash, for the password
 * sign-in path only (ADR-014). Deliberately a separate type from
 * {@link Account} itself so the hash never rides along through the rest of
 * the codebase (session issuance, {@code AuthResponse}, audit logging) where
 * {@code Account} is passed around freely.
 *
 * @param passwordHash null for an account that has only ever signed in via
 *     Google/Keycloak — never blank, never anything but a bcrypt hash.
 */
public record AccountCredential(Account account, String passwordHash) {}
