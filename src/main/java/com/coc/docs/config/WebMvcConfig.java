package com.coc.docs.config;

import com.coc.docs.auth.MustChangePasswordInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link MustChangePasswordInterceptor} over the whole API. The
 * handful of requests a caller carrying a temporary password still needs —
 * finding out who they are, ending the session, and replacing the password
 * itself — are allowed by the interceptor itself rather than excluded here;
 * see its Javadoc for why that decision has to be method-aware.
 *
 * <p>// req: ADR-017
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final MustChangePasswordInterceptor mustChangePasswordInterceptor;

    public WebMvcConfig(MustChangePasswordInterceptor mustChangePasswordInterceptor) {
        this.mustChangePasswordInterceptor = mustChangePasswordInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // No excludePathPatterns here: GET /api/v1/auth/me must stay reachable
        // while PATCH /api/v1/auth/me must not, and a path pattern cannot tell
        // those two apart — only the HTTP method does. That decision is method
        // -aware and lives in the interceptor itself.
        registry.addInterceptor(mustChangePasswordInterceptor).addPathPatterns("/api/v1/**");
    }
}
