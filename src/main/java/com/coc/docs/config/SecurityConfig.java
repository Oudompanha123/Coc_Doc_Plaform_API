package com.coc.docs.config;

import com.coc.docs.account.AppRole;
import com.coc.docs.auth.SessionAuthenticationFilter;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * The single authorisation chokepoint.
 *
 * <p>ADR-011 chose middleware enforcement over per-route checks precisely so
 * that adding a route cannot quietly add an unprotected route. That choice is
 * this file, and the property that makes it work is the last rule:
 * {@code anyRequest().hasRole(SUPER_ADMIN)}. A route nobody remembered to
 * declare is not open — it is reachable only by a super admin, which is
 * conspicuous enough to be found immediately. Default-deny inverts the risk, so
 * the failure mode of forgetfulness is a locked door rather than an open one.
 *
 * <p>This closes TM-019, modelled on exactly the shape where a new route ships
 * without a check.
 *
 * <p>// req: FR-AUTH-005, FR-AUTH-006, FR-AUTH-008, NFR-SEC-AUTH, ADR-011, ADR-012
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final AppProperties properties;

    public SecurityConfig(AppProperties properties) {
        this.properties = properties;
    }

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            SessionAuthenticationFilter sessionFilter,
            ProblemResponses problemResponses)
            throws Exception {

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // 401 for "no session", 403 for "wrong role". Without an explicit
                // entry point Spring Security answers 403 to both, and the
                // frontend cannot then tell a visitor who must sign in from one
                // who already has and lacks the role. See ProblemResponses.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(problemResponses)
                        .accessDeniedHandler(problemResponses))

                // CSRF protection is not needed and would be dead configuration.
                // The credential is a SameSite=Lax cookie, but that is not what
                // the argument rests on: the API accepts only application/json
                // bodies, which a cross-site form cannot produce, and the CORS
                // policy below allows exactly one origin. There is no
                // browser-initiated cross-site path that would carry the cookie.
                .csrf(csrf -> csrf.disable())

                // No servlet session. The credential is the app_session row, and
                // a container session would be a second, shorter-lived and
                // silently divergent notion of who the caller is.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .headers(headers -> headers
                        // Media is served from object storage, not from here, but
                        // an API response that a browser sniffs as HTML is the
                        // same class of problem. // req: NFR-SEC-UPLOAD
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.deny())
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000)))

                .addFilterBefore(sessionFilter, UsernamePasswordAuthenticationFilter.class)

                .authorizeHttpRequests(authorize -> authorize
                        // --- public: no session yet, by necessity -------------
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/nonce").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/google").permitAll()
                        // ADR-014: applying for an account, and signing in with one. Public
                        // by necessity — nobody has a session before either succeeds.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register").permitAll()
                        // ADR-021: the code-request/verify steps registration was split
                        // into. Same necessity as /register itself — no account, and
                        // therefore no session, exists until the last of these succeeds.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register/resend").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register/verify").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login-password").permitAll()
                        // ADR-018: starting or completing a password reset. Both public
                        // by the same necessity — a visitor who forgot their password,
                        // by definition, has no session to present.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/forgot-password").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/reset-password").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").permitAll()


                        // Self-service profile and session management. Any signed-in caller,

                        // including a plain user: these act on the account that made the

                        // request and cannot name another, so there is nothing here for a

                        // higher floor to protect. Without these three lines they would fall

                        // through to anyRequest(), which is super_admin.

                        .requestMatchers(HttpMethod.PATCH, "/api/v1/auth/me").hasRole(AppRole.USER.name())

                        // Self-service avatar upload/removal (ADR-026): same floor and
                        // the same reasoning as PATCH /auth/me above — acts on the
                        // caller's own account only.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/me/avatar").hasRole(AppRole.USER.name())
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/auth/me/avatar").hasRole(AppRole.USER.name())

                        // Self-service password change (revision of ADR-017): acts on
                        // the caller's own account, resolved from the session, and
                        // cannot name another — same floor as PATCH /auth/me above.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/change-password")
                        .hasRole(AppRole.USER.name())

                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/sessions").hasRole(AppRole.USER.name())

                        .requestMatchers(HttpMethod.DELETE, "/api/v1/auth/sessions/**")

                        .hasRole(AppRole.USER.name())
                        // Registering a device to be notified on (ADR-026). Any signed-in
                        // caller, not just an approver: only super admins receive
                        // anything today, but registering a destination is not the same
                        // act as being sent to, and gating it by role would both need
                        // changing the moment another notification exists and make the
                        // endpoint itself disclose who the approvers are. The account is
                        // taken from the session, never from the body, so a caller can
                        // only ever address their own.
                        .requestMatchers("/api/v1/notifications/devices/**")
                        .hasRole(AppRole.USER.name())

                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").permitAll()
                        // Liveness only. Carries no version, no configuration and
                        // no dependency status, so it discloses nothing.
                        .requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll()
                        // Browsers send a credential-free preflight; rejecting it
                        // would make every cross-origin call fail before the real
                        // request was ever sent.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // --- account management: super_admin floor ------------
                        // A fixed floor above admin, per FR-AUTH-008: an admin
                        // publishes content, and granting roles is a different
                        // kind of authority.
                        //
                        // This is also the whole of NFR-SEC-PII's enforcement.
                        // Every account's email and display name is reachable
                        // through this prefix and nowhere else in the API, so
                        // the personal-data boundary is this one line rather
                        // than a property spread across response types.
                        // // req: FR-AUTH-008, NFR-SEC-PII
                        // The role catalogue answers "what may an admin do",
                        // which is the same class of question as who holds a
                        // role — so it sits under the same super_admin floor,
                        // not the admin floor the rest of /admin carries.
                        .requestMatchers("/api/v1/admin/roles/**")
                        .hasRole(AppRole.SUPER_ADMIN.name())

                        .requestMatchers("/api/v1/admin/accounts/**")
                        .hasRole(AppRole.SUPER_ADMIN.name())

                        // --- editorial writes and admin reads ----------------
                        .requestMatchers("/api/v1/admin/**").hasRole(AppRole.ADMIN.name())

                        // --- reader path -------------------------------------
                        // The whole site sits at a `user` floor in the MVP.
                        // FR-PUBLIC-001 (guest reading) is Won't-have, and
                        // lowering specific pages is a phase-2 decision that
                        // BR-026 requires be made deliberately.
                        .requestMatchers(HttpMethod.GET, "/api/v1/sections/**").hasRole(AppRole.USER.name())
                        .requestMatchers(HttpMethod.GET, "/api/v1/pages/**").hasRole(AppRole.USER.name())

                        // Product docs pages (/docs/**, /jex3-0/**, ...) carry no
                        // auth gate on the frontend and are meant to be public —
                        // a deliberate, separate decision from the `user` floor
                        // above, not an oversight of it. Write access still sits
                        // behind the admin floor via /api/v1/admin/** below.
                        .requestMatchers(HttpMethod.GET, "/api/v1/docs-content/**").permitAll()

                        // The nav structure (product/sub-item list) drives the
                        // same public sidebar/docs pages as docs-content above,
                        // so it carries the same public-read exception.
                        .requestMatchers(HttpMethod.GET, "/api/v1/nav/**").permitAll()

                        // --- default deny ------------------------------------
                        // Deliberately the strictest role, not authenticated().
                        // See the class comment: this line is the control.
                        .anyRequest().hasRole(AppRole.SUPER_ADMIN.name()));

        return http.build();
    }

    /**
     * CORS for exactly one origin, with credentials.
     *
     * <p>The two services sit on different origins, which is a surface the
     * single-service design did not have (ADR-012). {@code allowCredentials} and
     * a wildcard origin are mutually exclusive for good reason, and the origin
     * here is configuration rather than a pattern so that a staging host cannot
     * be reached by a lookalike domain.
     *
     * <p>// req: NFR-SEC-CHANNEL, ADR-012
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(properties.frontendOrigin()));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "Accept", "If-Match"));
        // The frontend reads the canonical location out of a 301 when a slug has
        // been renamed, which a browser will not expose unless it is listed.
        config.setExposedHeaders(List.of("Location"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
