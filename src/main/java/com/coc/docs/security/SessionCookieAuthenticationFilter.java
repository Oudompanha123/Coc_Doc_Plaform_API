package com.coc.docs.security;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the {@code coc_session} cookie on every request and, if it names a
 * still-active session, authenticates the request as that account.
 *
 * <p>This is the "role resolved server-side, on every request" mechanism
 * ADR-011 §2 requires — nothing here ever trusts a client-supplied role
 * header, query parameter, or claim; the role always comes from the account
 * row {@link SessionService} resolves. // req: FR-AUTH-005
 */
@Component
public class SessionCookieAuthenticationFilter extends OncePerRequestFilter {

    public static final String SESSION_COOKIE_NAME = "coc_session";

    private final SessionService sessionService;

    public SessionCookieAuthenticationFilter(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String rawToken = readCookie(request, SESSION_COOKIE_NAME);
            if (rawToken != null) {
                sessionService.validate(rawToken).ifPresent(this::authenticate);
            }
        }
        chain.doFilter(request, response);
    }

    private void authenticate(Account account) {
        AccountPrincipal principal = AccountPrincipal.from(account);
        var authorities = List.of(new SimpleGrantedAuthority(account.role().authority()));
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
