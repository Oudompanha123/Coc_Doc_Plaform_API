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

    /**
     * Also run on the ASYNC dispatch, which {@link OncePerRequestFilter} skips
     * by default.
     *
     * <p>Without this, a response that goes asynchronous — the notification
     * stream (ADR-028), or anything returning an {@code SseEmitter} — is
     * authenticated on the way in and anonymous on the way back:
     *
     * <pre>
     * Securing GET /api/v1/notifications/stream
     * ERROR ... Servlet.service() for servlet [dispatcherServlet] threw exception
     * org.springframework.security.authorization.AuthorizationDeniedException: Access Denied
     * </pre>
     *
     * <p>The container dispatches the request a second time to finish the async
     * response. Spring Security's {@code AuthorizationFilter} runs on that
     * dispatch — it filters every dispatcher type by default, and that default
     * is the safe one, worth keeping. This filter did not, so the second pass
     * had an empty {@link SecurityContext} and the route was denied to a caller
     * who had just been let in. The error escapes to the servlet rather than
     * becoming a 401 because the response was already committed, which is what
     * makes the log read like a crash instead of a refusal.
     *
     * <p>Nothing is weakened by running again: the cookie is re-read and
     * re-resolved against the account row, so an account deactivated between
     * the two dispatches is not authenticated on the second. The alternative —
     * turning off authorization for async dispatches — would have made the
     * route work by not checking it.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
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
         * Grants the caller's rank and every rank beneath it, plus every
         * permission their roles carry.
         *
         * <p>The rank ladder is kept so a {@code hasRole("USER")} rule still
         * admits an admin without spelling out the ladder. Permissions are
         * added as plain authorities with no {@code ROLE_} prefix, so a rule
         * reads {@code hasAuthority("content.publish")} and cannot be confused
         * with {@code hasRole()}, which prefixes silently.
         *
         * <p>Both, because they answer different questions: {@code
         * page.min_role} asks for a rank, an endpoint asks for a capability
         * (ADR-016).
         */
        private static List<GrantedAuthority> authorities(CurrentUser user) {
            List<GrantedAuthority> granted = new java.util.ArrayList<>(
                    java.util.Arrays.stream(com.coc.docs.account.AppRole.values())
                            .filter(candidate -> user.role().isAtLeast(candidate))
                            .map(candidate -> (GrantedAuthority) new SimpleGrantedAuthority(candidate.authority()))
                            .toList());

            user.permissions()
                    .forEach(permission -> granted.add(new SimpleGrantedAuthority(permission.key())));

            return List.copyOf(granted);
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
