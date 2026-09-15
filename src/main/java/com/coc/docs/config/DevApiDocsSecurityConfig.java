package com.coc.docs.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Lets Swagger UI and the generated contract answer, on a developer machine and
 * nowhere else.
 *
 * <p>{@link SecurityConfig} ends in {@code anyRequest().hasRole(SUPER_ADMIN)},
 * and that default-deny is the control ADR-011 chose rather than an accident to
 * work around. Swagger UI is not under {@code /api/**}: it is an HTML page and
 * a bundle of JavaScript at {@code /swagger-ui/**}, fetching its contract from
 * {@code /v3/api-docs}. Without an exception every one of those requests is
 * answered 401 and the page renders as a blank frame with a console full of
 * failures.
 *
 * <p><b>Why a second chain rather than a few more {@code permitAll()} lines.</b>
 * Adding them to {@link SecurityConfig} would put profile-conditional rules
 * inside the one file whose value is that it can be read top-to-bottom as the
 * complete authorisation policy. Worse, it would make the production policy
 * depend on a condition that is false in production — the shape where someone
 * later edits the condition and opens a door nobody is looking at. This chain
 * instead carries a {@code securityMatcher} that admits only the documentation
 * paths, so {@link SecurityConfig} is byte-identical in every environment and
 * this class simply does not exist outside dev.
 *
 * <p>{@code @Order(1)} puts it ahead of {@link SecurityConfig}'s chain, which
 * takes the default lowest precedence. Spring Security walks chains in order
 * and uses the first whose matcher accepts the request, so documentation
 * requests stop here and everything else — every {@code /api/**} route — falls
 * through to the real policy untouched.
 *
 * <p>The other half of this is {@code springdoc.api-docs.enabled} /
 * {@code springdoc.swagger-ui.enabled}, false in {@code application.yml} and
 * true in {@code application-dev.yml}. Both halves are keyed to the dev profile
 * deliberately: with springdoc disabled these paths map to nothing, so even if
 * this class were somehow active it would be permitting 404s. Defence that does
 * not rely on one switch being set correctly.
 *
 * <p>// req: NFR-SEC-PII, ADR-011, ADR-012, RISK-018
 */
@Configuration
@Profile("dev")
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class DevApiDocsSecurityConfig {

    /**
     * The paths springdoc actually serves. {@code /v3/api-docs.yaml} is listed
     * separately because it is a sibling of {@code /v3/api-docs}, not a child:
     * {@code /v3/api-docs/**} does not match it, and it is the exact URL
     * {@code scripts/export-openapi.sh} fetches to regenerate
     * {@code docs/api/openapi.yaml}.
     */
    private static final String[] DOC_PATHS = {
        "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml"
    };

    @Bean
    SecurityFilterChain apiDocsFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(DOC_PATHS)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())

                // Scoped to DOC_PATHS by the matcher above, so this disables
                // nothing for the API itself. These are GETs of static assets
                // and a generated document; CSRF has no state change to protect
                // and would only add a token requirement to fetching a page.
                .csrf(csrf -> csrf.disable())

                // Swagger UI renders its operations inside frames. SecurityConfig
                // sends DENY, which is right for an API and wrong for this page,
                // and the setting is per-chain rather than global.
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));

        return http.build();
    }
}
