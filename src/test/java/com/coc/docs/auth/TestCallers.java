package com.coc.docs.auth;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import java.time.Instant;
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
        Account account = new Account(
                UUID.randomUUID(),
                role.wire() + "@example.com",
                "Test " + role.wire(),
                java.util.EnumSet.of(role),
                null,
                AccountStatus.ACTIVE,
                null,
                Instant.now(),
                Instant.now(),
                mustChangePassword);
        return new CurrentUser(account, com.coc.docs.account.TestPermissions.of(role), new byte[] {1, 2, 3});
    }

    /** The same authentication the real filter installs, for a caller of this role. */
    public static Authentication authentication(AppRole role) {
        return new SessionAuthenticationFilter.SessionAuthentication(user(role));
    }

    /** As {@link #authentication(AppRole)}, for a caller still under the first-login gate. */
    public static Authentication authentication(AppRole role, boolean mustChangePassword) {
        return new SessionAuthenticationFilter.SessionAuthentication(user(role, mustChangePassword));
    }
}
