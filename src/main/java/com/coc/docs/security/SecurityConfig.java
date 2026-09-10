package com.coc.docs.security;

import com.coc.docs.config.AppProperties;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * The backend-side half of ADR-011 §2/§2.5: default-deny authentication,
 * server-resolved roles, and cross-origin cookie handling proportionate to a
 * 6-person internal tool (no bespoke CSRF-token framework).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final SessionCookieAuthenticationFilter sessionCookieAuthenticationFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final AppProperties appProperties;

    public SecurityConfig(
            SessionCookieAuthenticationFilter sessionCookieAuthenticationFilter,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            AppProperties appProperties) {
        this.sessionCookieAuthenticationFilter = sessionCookieAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.appProperties = appProperties;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // Deliberately disabled, not omitted: ADR-011 §2.5's default
                // (D1, shared parent domain + SameSite=Lax) is chosen
                // specifically so this team never has to build a CSRF-token
                // framework. If the D2 fallback (SameSite=None) is ever
                // adopted instead, a mandatory Origin-header check must ship
                // alongside it FIRST — see the TODO below — rather than
                // re-enabling this as a substitute.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // We issue and validate our own opaque session cookie against
                // app_session (ADR-011 §2); Spring's HttpSession/JSESSIONID
                // is never used.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(authorize -> authorize
                        // CORS preflight: the browser's OPTIONS probe carries
                        // no credentials and grants no access by itself: it
                        // never reaches a controller. Spring's CorsFilter adds
                        // the response headers but still passes the request
                        // through this chain, so without this line every
                        // preflight for a real (authenticated) endpoint would
                        // be rejected before the actual request is ever sent.
                        .requestMatchers(HttpMethod.OPTIONS, "/**")
                        .permitAll()
                        // Default-deny (ADR-011 §2.5): every route requires
                        // authentication unless explicitly allow-listed here,
                        // and this list must stay small and reviewed.
                        // // req: FR-AUTH-005, TM-019, TM-027
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/auth/nonce",
                                "/api/auth/login",
                                // ADR-014's second, password-based sign-in
                                // path — same "unauthenticated by necessity"
                                // reasoning as the two routes above.
                                "/api/auth/register",
                                "/api/auth/login-password")
                        .permitAll()
                        .requestMatchers("/actuator/health/**")
                        .permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .addFilterBefore(sessionCookieAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);

        // TODO(ADR-011 §2.5, follow-up item, go-live blocking — NOT a
        // G2/Sprint-1 blocker): a dedicated Origin-header validation filter
        // for every state-changing request, required only if the D2 fallback
        // (SameSite=None, no shared parent domain) is adopted instead of the
        // D1 default. Not built here because (a) the architect/DevOps
        // domain-topology decision it depends on is still open per ADR-011
        // §4.3, and (b) it is a project-wide concern spanning every write
        // endpoint this API will ever have, not something specific to login.

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Exact origin allow-list only — never a wildcard, never a
        // reflected Origin header. ADR-011 §2.5, TM-023.
        List<String> origins = Arrays.stream(appProperties.frontendOrigin().split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "Accept"));
        // Paired deliberately with the exact allow-list above — this
        // combination is what makes credentialed calls work for the
        // legitimate frontend and fail for everyone else (TM-023).
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * Bcrypt, for the password self-registration path only (ADR-014) — the
     * Google/Keycloak path never touches this bean. A work factor of 12
     * (double BCrypt's default 10 rounds) given this runs on request-time,
     * human-facing endpoints, not a hot path.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * The role ladder itself (FR-AUTH-005): {@code guest < user < admin < super_admin}.
     * Defined once so a {@code @PreAuthorize("hasRole('USER')")} on a later
     * endpoint is automatically satisfied by an {@code admin} or
     * {@code super_admin} caller too, instead of every future authorization
     * rule re-deriving the ladder by hand.
     *
     * <p>{@code @EnableMethodSecurity} above picks this bean up automatically
     * for {@code @PreAuthorize}/{@code @PostAuthorize}. Confirm the same
     * holds for any {@code .hasRole(...)} used directly in a future
     * {@code authorizeHttpRequests} chain before relying on it there — this
     * login API's own filter chain only uses {@code .authenticated()}/
     * {@code .permitAll()}, so that path isn't exercised yet.
     */
    @Bean
    public RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role("SUPER_ADMIN").implies("ADMIN")
                .role("ADMIN").implies("USER")
                .role("USER").implies("GUEST")
                .build();
    }
}
