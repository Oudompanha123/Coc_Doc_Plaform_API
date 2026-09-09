package com.coc.docs.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns the session cookie into an authenticated {@link SecurityContext}.
 *
 * <p>Runs on every request. The authority granted comes from
 * {@link SessionService#resolve}, which reads the account row — so the role
 * reflects the database at the moment of the request, and a client that sends a
 * role header, a role cookie or a crafted JWT contributes nothing.
 *
 * <p>A missing or dead session leaves the context empty rather than rejecting
 * here. Rejection is the filter chain's job: this filter does not know which
 * routes are public, and duplicating that knowledge is how the two drift apart.
 *
 * <p>// req: FR-AUTH-005, FR-AUTH-006, ADR-011, TM-003
 */
@Component
public class SessionAuthenticationFilter extends OncePerRequestFilter {

    private final SessionService sessions;

    public SessionAuthenticationFilter(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            readCookie(request)
                    .flatMap(sessions::resolve)
                    .ifPresent(SessionAuthenticationFilter::authenticate);
        }
        chain.doFilter(request, response);
    }

    private static void authenticate(CurrentUser user) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new SessionAuthentication(user));
        SecurityContextHolder.setContext(context);
    }

    private static Optional<String> readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (SessionService.COOKIE_NAME.equals(cookie.getName())) {
                return Optional.ofNullable(cookie.getValue()).filter(v -> !v.isBlank());
            }
        }
        return Optional.empty();
    }

    /**
     * The authentication object for a resolved session.
     *
     * <p>Credentials are {@code null} on purpose: the token is not kept past
     * resolution, so it cannot be read back out of the security context by
     * anything logging the authentication object.
     */
    public static class SessionAuthentication extends AbstractAuthenticationToken {

        private static final long serialVersionUID = 1L;

        private final transient CurrentUser user;

        SessionAuthentication(CurrentUser user) {
            super(authorities(user));
            this.user = user;
            setAuthenticated(true);
        }

        /**
         * Grants the caller's own role and every role beneath it, so a
         * {@code hasRole("USER")} rule admits an admin without each rule having
         * to spell out the whole ladder.
         */
        private static List<GrantedAuthority> authorities(CurrentUser user) {
            return java.util.Arrays.stream(com.coc.docs.account.AppRole.values())
                    .filter(candidate -> user.role().isAtLeast(candidate))
                    .map(candidate -> (GrantedAuthority) new SimpleGrantedAuthority(candidate.authority()))
                    .toList();
        }

        @Override
        public Object getCredentials() {
            return null;
        }

        @Override
        public CurrentUser getPrincipal() {
            return user;
        }

        @Override
        public String getName() {
            return user.email();
        }
    }
}
