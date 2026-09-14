package com.coc.docs.auth;

import com.coc.docs.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Defense in depth for the first-login flow (revision of ADR-017): a caller
 * whose account still carries a {@code super_admin}-issued temporary
 * password is refused everything except the small set of requests that let
 * them replace it.
 *
 * <p>{@code RequireSession}/{@code SessionProvider} on the frontend already
 * redirect to Create New Password the moment {@code /auth/me} reports
 * {@code mustChangePassword: true} — this is the control that holds when that
 * redirect is skipped, bypassed, or never runs, matching ADR-011's
 * "middleware enforcement, not a courtesy the client can opt out of"
 * philosophy.
 *
 * <p>The allowlist is checked here, by method and path together, rather than
 * as {@code excludePathPatterns} on the {@code InterceptorRegistry}
 * ({@code WebMvcConfig}): a path pattern cannot tell {@code GET /auth/me}
 * (must stay reachable — it is how the frontend learns
 * {@code mustChangePassword} at all) apart from {@code PATCH /auth/me} (must
 * not — it would let a caller rename their own account while dodging the
 * gate), and excluding the path would have excluded both.
 *
 * <p>Registered via {@code WebMvcConfig} rather than folded into {@link
 * SessionAuthenticationFilter}: that filter's job is resolving who the caller
 * is, and this is a separate question — whether that resolved caller's
 * account is otherwise not eligible to be it yet.
 *
 * <p>// req: FR-AUTH-002, FR-AUTH-005, ADR-011, ADR-017
 */
@Component
public class MustChangePasswordInterceptor implements HandlerInterceptor {

    /** Reachable regardless of {@code mustChangePassword} — see the class Javadoc. */
    private static final Set<String> ALWAYS_ALLOWED = Set.of(
            HttpMethod.GET.name() + " /api/v1/auth/me",
            HttpMethod.POST.name() + " /api/v1/auth/change-password",
            HttpMethod.POST.name() + " /api/v1/auth/logout",
            HttpMethod.GET.name() + " /api/v1/health");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (ALWAYS_ALLOWED.contains(request.getMethod() + " " + request.getRequestURI())) {
            return true;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof SessionAuthenticationFilter.SessionAuthentication sessionAuth)) {
            // No resolved caller (anonymous, or a test substituting its own
            // authentication type) — nothing for this gate to act on.
            return true;
        }

        CurrentUser caller = sessionAuth.getPrincipal();
        if (!caller.account().mustChangePassword()) {
            return true;
        }

        throw ApiException.forbidden(
                "a new password is required before this account can be used further; "
                        + "see POST /api/v1/auth/change-password");
    }
}
