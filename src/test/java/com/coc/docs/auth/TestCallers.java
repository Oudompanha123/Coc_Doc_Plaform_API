package com.coc.docs.auth;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import com.coc.docs.capability.Capability;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;

/**
 * Builds the authentication object a resolved session produces, for tests.
 *
 * <p>Lives in this package so that {@code SessionAuthentication} can stay
 * package-private in production code. A test needing a caller is not a reason to
 * widen an API.
 *
 * <p>Deliberately not {@code @WithMockUser}: that grants an authority string of
 * its own choosing, and the thing worth testing is that {@link AppRole}'s ladder
 * and the rule table in {@code SecurityConfig} agree. A test that invented its
 * own authorities could pass while the real mapping was wrong.
 *
 * <p>// req: FR-AUTH-005
 */
public final class TestCallers {

    private TestCallers() {}

    public static CurrentUser user(AppRole role) {
        return user(role, false);
    }

    /** As {@link #user(AppRole)}, for a caller still under the first-login gate. */
    public static CurrentUser user(AppRole role, boolean mustChangePassword) {
        return user(role, mustChangePassword, true);
    }

    /**
     * As {@link #user(AppRole, boolean)}, also choosing whether the account
     * holds a password credential at all (ADR-030) — pass {@code false} for a
     * Google-only caller.
     */
    public static CurrentUser user(AppRole role, boolean mustChangePassword, boolean hasPassword) {
        return user(role, mustChangePassword, hasPassword, Set.of());
    }

    /**
     * As {@link #user(AppRole, boolean, boolean)}, also holding the given
     * capabilities (ADR-037) — for testing a rule that admits {@code
     * hasAnyAuthority(ROLE_X, capability.developer)} regardless of rank.
     */
    public static CurrentUser user(AppRole role, boolean mustChangePassword, boolean hasPassword, Set<Capability> capabilities) {
        Account account = new Account(
                UUID.randomUUID(),
                role.wire() + "@example.com",
                "Test " + role.wire(),
                java.util.EnumSet.of(role),
                null,
                null,
                AccountStatus.ACTIVE,
                null,
                Instant.now(),
                Instant.now(),
                mustChangePassword,
                hasPassword);
        return new CurrentUser(
                account, com.coc.docs.account.TestPermissions.of(role), capabilities, new byte[] {1, 2, 3});
    }

    /** The same authentication the real filter installs, for a caller of this role. */
    public static Authentication authentication(AppRole role) {
        return new SessionAuthenticationFilter.SessionAuthentication(user(role));
    }

    /** As {@link #authentication(AppRole)}, also holding the given capabilities (ADR-037). */
    public static Authentication authentication(AppRole role, Set<Capability> capabilities) {
        return new SessionAuthenticationFilter.SessionAuthentication(user(role, false, true, capabilities));
    }

    /** As {@link #authentication(AppRole)}, for a caller still under the first-login gate. */
    public static Authentication authentication(AppRole role, boolean mustChangePassword) {
        return new SessionAuthenticationFilter.SessionAuthentication(user(role, mustChangePassword));
    }

    /** As {@link #authentication(AppRole, boolean)}, also choosing {@code hasPassword} (ADR-030). */
    public static Authentication authentication(AppRole role, boolean mustChangePassword, boolean hasPassword) {
        return new SessionAuthenticationFilter.SessionAuthentication(
                user(role, mustChangePassword, hasPassword));
    }
}
