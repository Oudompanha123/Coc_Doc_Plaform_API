package com.coc.docs.auth;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import java.time.Instant;
import java.util.EnumSet;
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
        return user(EnumSet.of(role), mustChangePassword, hasPassword);
    }

    /**
     * As {@link #user(AppRole, boolean, boolean)}, holding the given roles all
     * at once (ADR-016) rather than exactly one — for testing a rule that
     * distinguishes exact role membership from rank (e.g. {@code
     * AppRole.DEVELOPER.exactAuthority()}, ADR-039), where an account ranked
     * above {@code DEVELOPER} must not automatically pass.
     */
    public static CurrentUser user(Set<AppRole> roles, boolean mustChangePassword, boolean hasPassword) {
        return user(roles, roles.iterator().next().wire() + "@example.com", mustChangePassword, hasPassword);
    }

    /**
     * As {@link #user(Set, boolean, boolean)}, with a specific email instead
     * of the auto-generated one — for a test of the HRMS-row-by-email rule
     * ({@code HrmsMailContent#onlyRowsFor}), where the email itself is what
     * is under test, not the role.
     */
    public static CurrentUser user(Set<AppRole> roles, String email, boolean mustChangePassword, boolean hasPassword) {
        Account account = new Account(
                UUID.randomUUID(),
                email,
                "Test " + roles,
                EnumSet.copyOf(roles),
                null,
                null,
                AccountStatus.ACTIVE,
                null,
                null,
                Instant.now(),
                Instant.now(),
                mustChangePassword,
                hasPassword);
        return new CurrentUser(account, com.coc.docs.account.TestPermissions.of(roles), new byte[] {1, 2, 3});
    }

    /** As {@link #user(AppRole)}, with a specific email instead of the auto-generated one. */
    public static CurrentUser user(AppRole role, String email) {
        return user(EnumSet.of(role), email, false, true);
    }

    /** As {@link #user(Set, boolean, boolean)}, active and with a password. */
    public static CurrentUser user(Set<AppRole> roles) {
        return user(roles, false, true);
    }

    /** The same authentication the real filter installs, for a caller of this role. */
    public static Authentication authentication(AppRole role) {
        return new SessionAuthenticationFilter.SessionAuthentication(user(role));
    }

    /** As {@link #authentication(AppRole)}, for a caller holding every one of these roles at once (ADR-016). */
    public static Authentication authentication(Set<AppRole> roles) {
        return new SessionAuthenticationFilter.SessionAuthentication(user(roles));
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
