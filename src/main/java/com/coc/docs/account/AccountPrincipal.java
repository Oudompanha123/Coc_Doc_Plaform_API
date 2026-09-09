package com.coc.docs.account;

import java.util.UUID;

/**
 * The authenticated principal placed in the {@link org.springframework.security.core.context.SecurityContext}
 * once {@code SessionCookieAuthenticationFilter} resolves a session cookie to
 * an active account. Bind it in a controller with {@code @AuthenticationPrincipal}.
 *
 * <p>Lives in {@code account}, not {@code security} or {@code auth}, so both of
 * those packages can depend on it without depending on each other.
 */
public record AccountPrincipal(UUID id, String email, String displayName, Role role) {

    public static AccountPrincipal from(Account account) {
        return new AccountPrincipal(account.id(), account.email(), account.displayName(), account.role());
    }
}
