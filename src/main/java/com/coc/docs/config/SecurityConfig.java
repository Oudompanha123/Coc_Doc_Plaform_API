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
import jakarta.servlet.DispatcherType;
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
                        // --- the container finishing a response it already
                        //     authorised ----------------------------------------
                        //
                        // An SseEmitter response is dispatched twice: once as a
                        // REQUEST, which is where the decision is made, and once
                        // as ASYNC, when the container comes back to finish it.
                        // Spring Security authorises both by default.
                        //
                        // Re-deciding on the second one protects nothing. The
                        // body has already been streamed, so a refusal takes
                        // nothing back — and it cannot even be reported, because
                        // the response is committed:
                        //
                        //   Unable to handle the Spring Security Exception
                        //   because the response is already committed
                        //
                        // which arrives as a hundred-frame stack trace every
                        // time a long-lived stream outlives its session. And
                        // they do outlive them: signing in again revokes the
                        // session it replaces, and any stream still open on the
                        // old one is then denied on its way out.
                        //
                        // An ASYNC dispatch cannot happen for a request that was
                        // never authorised in the first place, so permitting it
                        // widens nothing. SessionAuthenticationFilter still runs
                        // on it, so the principal is there for anything that
                        // needs one.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()

                        // --- public: no session yet, by necessity -------------
                        // Profile photos (ADR-101). Public because an <img> does
                        // not reliably carry the session cookie across origins,
                        // and a cookie that did would need SameSite=None — a
                        // worse trade than relying on the random object key.
                        // GET only: nothing here writes, and the route refuses
                        // any key the named account does not actually hold.
                        // // req: FR-AUTH-005, NFR-SEC-PII, ADR-101
                        .requestMatchers(HttpMethod.GET, "/api/v1/avatars/**").permitAll()
                        // Store app logos (ADR-108): public for the same <img>
                        // reason as profile photos just above. A logo is only a
                        // picture, addressed by a random app id; nothing about
                        // an app's builds is reachable without a session.
                        // // req: ADR-108
                        .requestMatchers(HttpMethod.GET, "/api/v1/store/apps/*/logo").permitAll()
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
                        // ADR-031: redeeming the pending-MFA cookie a correct password just
                        // left behind. Public by the same necessity as /login-password itself
                        // -- no session exists yet, and what stands in for one here is the
                        // pending cookie, not this route's own permissions.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/mfa/verify").permitAll()
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

                        // Self-service deactivation (ADR-042): any signed-in account may
                        // deactivate itself, unlike /api/v1/admin/accounts/** below, which
                        // stays a fixed super_admin floor for acting on someone else's
                        // account. This route only ever acts on the caller's own row,
                        // resolved from the session — same reasoning as PATCH /auth/me.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/me/deactivate")
                        .hasRole(AppRole.USER.name())

                        // Self-service TOTP enrollment (ADR-031): same floor and reasoning
                        // as change-password above -- acts on the caller's own account,
                        // resolved from the session, and cannot name another.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/mfa/setup/start").hasRole(AppRole.USER.name())
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/mfa/setup/verify").hasRole(AppRole.USER.name())
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/mfa/disable").hasRole(AppRole.USER.name())
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/mfa/status").hasRole(AppRole.USER.name())

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

                        // The live notification stream, same rule and same
                        // reason: it carries only this account's own events,
                        // resolved from the session, so there is nothing here
                        // to point at anybody else. A long-lived response
                        // rather than a request, but the authorisation question
                        // is asked once, at the same place, before it opens.
                        .requestMatchers(HttpMethod.GET, "/api/v1/notifications/stream")
                        .hasRole(AppRole.USER.name())

                        // The caller's own notifications, and marking them read.
                        // Any signed-in account, because every role receives
                        // some of them — a new-device sign-in alert goes to
                        // whoever was signed in to, whatever they may do here
                        // (ADR-034). The rows are scoped to the resolved caller
                        // in the query itself, so there is nothing to point at
                        // anybody else.
                        .requestMatchers("/api/v1/notifications/inbox/**")
                        .hasRole(AppRole.USER.name())

                        // The caller's own notification preferences (ADR-042).
                        // Same reasoning as the inbox rule above: every role
                        // can receive some notification type, and the account
                        // is the resolved caller's, never one from the URL, so
                        // there is nothing here to point at anybody else.
                        .requestMatchers("/api/v1/notifications/preferences/**")
                        .hasRole(AppRole.USER.name())

                        // The topbar's activity feed. Read-only, and a `user`
                        // floor rather than anything higher because every role
                        // is entitled to their own history.
                        //
                        // This does NOT widen who can see account identities.
                        // NotificationFeedService selects another account's rows
                        // only for a super_admin; for every other role the query
                        // filters on their own actor_id, so there is no other
                        // person's email in the response to leak (NFR-SEC-PII).
                        // That scope decision lives in the service because it is
                        // a row filter, which a URL pattern cannot express — the
                        // one place in this chain where that is true, and the
                        // reason this comment exists.
                        //
                        // GET only, and exact-path: POST /api/v1/admin/
                        // notifications/digest sends real mail and keeps its own
                        // super_admin rule further down. Nothing here touches it.
                        // // req: FR-OPS-001, FR-AUTH-008, NFR-SEC-PII
                        .requestMatchers(HttpMethod.GET, "/api/v1/notifications")
                        .hasRole(AppRole.USER.name())

                        // One HRMS attendance report, fetched from the mailbox on
                        // the request and never stored (ADR-042).
                        //
                        // Needs a line of its own because the matcher above is
                        // exact-path: without this, the route would fall through to
                        // anyRequest() rather than inherit the neighbour it looks
                        // like it belongs to.
                        //
                        // THE FLOOR IS DELIBERATE AND IT IS LOW. This response
                        // carries ~60 colleagues' check-in and check-out times --
                        // the only route in this chain that returns personal data
                        // about people who are not users of this system. Every
                        // other PII-bearing route here is super_admin, and
                        // NotificationFeedService used to say that if this content
                        // ever named people the floor must move UP. The PM decided
                        // the other way; ADR-042 records that, with what it weighed.
                        // Anyone narrowing this later needs to read that ADR first,
                        // not just this comment.
                        // // req: FR-OPS-001, NFR-SEC-PII
                        .requestMatchers(HttpMethod.GET, "/api/v1/notifications/hrms/*")
                        .hasRole(AppRole.USER.name())

                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").permitAll()

                        // A Claude Code usage reporter script, not a browser with a
                        // session -- there is no session to have. Outside /api/v1/**
                        // by design (ClaudeUsageIngestController's own Javadoc) and
                        // authorized by ClaudeUsageIngestController itself against a
                        // single shared bearer secret, never by this filter chain.
                        // This is the one deliberate exception to "every route is
                        // enforced from a session" (ADR-011) -- NFR-SEC-CHANNEL says
                        // in so many words that it is not a template for a second one.
                        // // req: FR-USAGE-002, NFR-SEC-CHANNEL, ADR-035
                        .requestMatchers(HttpMethod.POST, "/api/ingest").permitAll()
                        // Its scan-only sibling (ADR-097): same controller, same
                        // bearer check, records only when the reporter scanned.
                        .requestMatchers(HttpMethod.POST, "/api/ingest/scan").permitAll()
                        // And its scan-log sibling (ADR-099): same controller, same
                        // bearer check, records how one scan went.
                        .requestMatchers(HttpMethod.POST, "/api/ingest/scan-log").permitAll()
                        // And the scan's start (ADR-107): same controller, same
                        // bearer check, marks the scan "In progress" until its log.
                        .requestMatchers(HttpMethod.POST, "/api/ingest/scan-log/start").permitAll()

                        // The claude-team-usage CLI's code exchange (ADR-060): a CLI
                        // has no session either. Protected by a one-time, two-minute
                        // code plus the PKCE verifier only that CLI process holds,
                        // checked in ClaudeUsageCliAuthService#exchange -- the approval
                        // that issues the code is session-authenticated, under the
                        // /api/v1/me/usage-tokens/** USER floor below. Same exception
                        // class as /api/ingest, not a new kind of one.
                        // // req: FR-USAGE-002, NFR-SEC-CHANNEL, ADR-060
                        .requestMatchers(HttpMethod.POST, "/api/cli/token").permitAll()

                        // Self-service /api/ingest tokens (ADR-047, revision of ADR-035
                        // §2): the human minting/listing/revoking their own credential,
                        // unlike POST /api/ingest above which is the machine presenting
                        // one. Floored at USER, widened from ADMIN on direct developer
                        // instruction (ADR-053) so any signed-in account can self-mint
                        // the token its own "Download push-usage.mjs" button needs --
                        // resolved from the session as usual, never from the body.
                        // // req: FR-USAGE-002, NFR-SEC-CHANNEL, ADR-050, ADR-053
                        .requestMatchers("/api/v1/me/usage-tokens/**").hasRole(AppRole.USER.name())

                        // The caller's own sending credential (ADR-063). USER, because
                        // it is self-service: the route carries no id and always
                        // resolves the account from the session, so a caller cannot
                        // reach anybody else's. Needs its own line -- without one it
                        // falls through to anyRequest()/super_admin and only an
                        // administrator could set their own, which is the opposite of
                        // what a self-service credential is for.
                        // // req: NFR-SEC-PII, NFR-SEC-CHANNEL, ADR-063
                        // "/**" and not the bare path: the OAuth exchange lives
                        // at /mail-credential/google (ADR-085), and an exact
                        // matcher would drop it through to anyRequest() and
                        // super_admin -- so only an administrator could connect
                        // their own Gmail, which is the opposite of self-service.
                        .requestMatchers("/api/v1/me/mail-credential/**").hasRole(AppRole.USER.name())

                        // The personal "My Claude Usage" screen (ADR-052):
                        // every signed-in account's own read of its own
                        // usage, unlike AdminClaudeUsageController's
                        // team-wide roster below, which stays ADMIN+. USER
                        // floor, not ADMIN -- ClaudeUsageMeController
                        // resolves the caller's rows from the session email,
                        // never from a request parameter, so widening this
                        // floor cannot let one account read another's usage.
                        // // req: FR-USAGE-005, ADR-052
                        .requestMatchers(HttpMethod.GET, "/api/v1/claude-usage/me").hasRole(AppRole.USER.name())
                        // The caller's own scan logs (ADR-107), same reasoning:
                        // ClaudeUsageMeController reads them by the session's
                        // email, and someone else's scan id is a 404. The
                        // team-wide scan logs stay under
                        // /api/v1/admin/claude-usage at the super_admin floor.
                        // // req: FR-USAGE-002, NFR-SEC-PII, ADR-107
                        .requestMatchers(HttpMethod.GET, "/api/v1/claude-usage/me/scan-logs", "/api/v1/claude-usage/me/scan-logs/*")
                        .hasRole(AppRole.USER.name())

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
                        // This is also most of NFR-SEC-PII's enforcement. Every
                        // account's email and display name is reachable
                        // through this prefix (and, since AuditRepository's
                        // reads went live, one other: the audit trail below,
                        // gated at this same floor for the same reason), so
                        // the personal-data boundary is these two lines
                        // rather than a property spread across response types.
                        // // req: FR-AUTH-008, NFR-SEC-PII
                        // The role catalogue answers "what may an admin do",
                        // which is the same class of question as who holds a
                        // role — so it sits under the same super_admin floor,
                        // not the admin floor the rest of /admin carries.
                        .requestMatchers("/api/v1/admin/roles/**")
                        .hasRole(AppRole.SUPER_ADMIN.name())

                        .requestMatchers("/api/v1/admin/accounts/**")
                        .hasRole(AppRole.SUPER_ADMIN.name())

                        // Sending a content-activity digest on demand. Above the
                        // admin floor deliberately, and stated here rather than
                        // left to the /admin/** line below, which would have
                        // admitted any admin by default:
                        //
                        //   - the mail carries page titles and the names of the
                        //     people who edited them, so this is the same
                        //     personal-data boundary as the accounts prefix
                        //     (NFR-SEC-PII);
                        //   - it puts mail in other people's inboxes, which is
                        //     not editorial work — an admin publishes content
                        //     (FR-AUTH-008).
                        //
                        // // req: FR-AUTH-008, NFR-SEC-PII, FR-OPS-001
                        .requestMatchers("/api/v1/admin/notifications/**")
                        .hasRole(AppRole.SUPER_ADMIN.name())

                        // AuditRepository's own Javadoc requires this floor: every
                        // row carries another account's actor_email, so a reader
                        // over it is the same class of PII exposure as the
                        // accounts endpoints above, not an ordinary editorial read.
                        .requestMatchers("/api/v1/admin/audit/**")
                        .hasRole(AppRole.SUPER_ADMIN.name())

                        // The team's HRMS clock-ins and its Claude Usage roster
                        // (with each member's activity detail): both are data
                        // about other people, the same personal-data boundary
                        // as the accounts prefix, so the same floor (ADR-100).
                        // Stated here rather than left to the /admin/** line
                        // below, which would admit any admin.
                        // // req: NFR-SEC-PII, ADR-100
                        .requestMatchers("/api/v1/admin/attendance/**")
                        .hasRole(AppRole.SUPER_ADMIN.name())
                        .requestMatchers("/api/v1/admin/claude-usage/**")
                        .hasRole(AppRole.SUPER_ADMIN.name())

                        // A task's title carries no other account's PII, unlike the
                        // two prefixes above — so the read side is open to any
                        // signed-in role, for the Member landing page's "Your
                        // Tasks" list (ADR-033). Creating/completing a task stays
                        // admin+ only, falling through to the generic /admin/**
                        // rule below since this matcher is GET-only.
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/tasks")
                        .hasRole(AppRole.USER.name())

                        // --- AX Renewal (ADR-090, revising ADR-086 for this --
                        // one feature) ------------------------------------------
                        // Its own prefix, not under /api/v1/admin/**: Developer,
                        // Reviewer and Lead reach this surface (ADR-090) while
                        // the rest of the admin app stays [admin, super_admin]
                        // only (ADR-086). Reading and commenting floor at
                        // DEVELOPER by rank — Developer, Reviewer, Lead, Admin,
                        // Super Admin; never user or guest. Which projects a
                        // caller sees is still membership (ADR-070).
                        .requestMatchers(HttpMethod.GET, "/api/v1/ax-renewal/**")
                        .hasRole(AppRole.DEVELOPER.name())
                        .requestMatchers(HttpMethod.POST, "/api/v1/ax-renewal/projects/*/comments")
                        .hasRole(AppRole.DEVELOPER.name())
                        // Removing one stays at the same floor as writing one:
                        // whether this particular caller may remove this
                        // particular comment depends on who wrote it, which is
                        // row data no matcher can see. AxRenewalService makes
                        // that call — author, or super_admin (ADR-045).
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/ax-renewal/projects/*/comments/*")
                        .hasRole(AppRole.DEVELOPER.name())

                        .requestMatchers(HttpMethod.POST, "/api/v1/ax-renewal/projects").hasRole(AppRole.ADMIN.name())
                        // Deleting a project is the symmetric lifecycle action
                        // to creating one, so it gets the same floor, not a
                        // higher one (ADR-051) — AxRenewalService still refuses
                        // it (409) while an implementation run or a local dev
                        // server is active, which is a business rule, not an
                        // authorisation decision, so it does not live here.
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/ax-renewal/projects/*")
                        .hasRole(AppRole.ADMIN.name())
                        // Renaming / re-describing a project (ADR-076): the
                        // same project-lifecycle floor as creating or deleting
                        // one. Whether this caller is on the project is row
                        // data, checked by AxRenewalService.
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/ax-renewal/projects/*")
                        .hasRole(AppRole.ADMIN.name())
                        // Project members (ADR-069): who is on the team is a
                        // project-lifecycle decision, the same floor as
                        // creating the project. Whether this caller is on
                        // *this* project is row data, so AxRenewalService
                        // checks that, not this matcher. Listing members is a
                        // plain read — it comes with the project detail GET.
                        .requestMatchers(HttpMethod.POST, "/api/v1/ax-renewal/projects/*/members")
                        .hasRole(AppRole.ADMIN.name())
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/ax-renewal/projects/*/members/*")
                        .hasRole(AppRole.ADMIN.name())
                        .requestMatchers(HttpMethod.POST, "/api/v1/ax-renewal/projects/*/manifest")
                        .hasRole(AppRole.ADMIN.name())
                        .requestMatchers(HttpMethod.POST, "/api/v1/ax-renewal/projects/*/analysis/run")
                        .hasRole(AppRole.ADMIN.name())
                        // Assistant chat sits at the analysis floor, not the
                        // comment floor it superficially resembles: both this
                        // and Run analysis can point a local model at the
                        // source folder, and reading the source is the thing
                        // being gated. Writing a comment cannot (ADR-046).
                        .requestMatchers(HttpMethod.POST, "/api/v1/ax-renewal/projects/*/assistant/chat")
                        .hasRole(AppRole.ADMIN.name())
                        .requestMatchers(HttpMethod.PUT, "/api/v1/ax-renewal/projects/*/plan/**")
                        .hasRole(AppRole.ADMIN.name())
                        .requestMatchers(HttpMethod.PUT, "/api/v1/ax-renewal/projects/*/database/connect")
                        .hasRole(AppRole.ADMIN.name())
                        .requestMatchers(HttpMethod.PUT, "/api/v1/ax-renewal/projects/*/destination")
                        .hasRole(AppRole.ADMIN.name())

                        // Confirming a section, starting implementation, and
                        // every action below that writes or runs code: Admin or
                        // above (ADR-086 widened these to a plain Admin), or an
                        // account that holds Developer *exactly*
                        // (AppRole.exactAuthority(), ADR-090 restoring ADR-040's
                        // carve-out). Exact, not rank: Reviewer and Lead
                        // out-rank Developer but stay read-and-comment only.
                        .requestMatchers(HttpMethod.POST, "/api/v1/ax-renewal/projects/*/plan/*/confirm")
                        .hasAnyAuthority(AppRole.ADMIN.authority(), AppRole.DEVELOPER.exactAuthority())
                        .requestMatchers(HttpMethod.POST, "/api/v1/ax-renewal/projects/*/plan/*/reopen")
                        .hasAnyAuthority(AppRole.ADMIN.authority(), AppRole.DEVELOPER.exactAuthority())
                        .requestMatchers(HttpMethod.POST, "/api/v1/ax-renewal/projects/*/implementation/start")
                        .hasAnyAuthority(AppRole.ADMIN.authority(), AppRole.DEVELOPER.exactAuthority())
                        // Approve/reject a Generate run's proposed changes (ADR-044) —
                        // Approve is the one action in this whole feature that writes
                        // outside this application's own database, so it gets exactly
                        // the same floor as starting implementation in the first place,
                        // not a lower one.
                        .requestMatchers(HttpMethod.POST, "/api/v1/ax-renewal/projects/*/implementation/*/approve")
                        .hasAnyAuthority(AppRole.ADMIN.authority(), AppRole.DEVELOPER.exactAuthority())
                        .requestMatchers(HttpMethod.POST, "/api/v1/ax-renewal/projects/*/implementation/*/reject")
                        .hasAnyAuthority(AppRole.ADMIN.authority(), AppRole.DEVELOPER.exactAuthority())
                        // Cancel a still-generating run (ADR-049) — kills a
                        // real process on the backend's own machine, the
                        // same class of action as the three routes above.
                        .requestMatchers(HttpMethod.POST, "/api/v1/ax-renewal/projects/*/implementation/*/cancel")
                        .hasAnyAuthority(AppRole.ADMIN.authority(), AppRole.DEVELOPER.exactAuthority())
                        // AI Error Assistant / UI Customization, Phase 2
                        // (ADR-062) — starting either kind of Generate run
                        // and running tests all execute real code on the
                        // backend's own machine, the same class of action as
                        // start/approve/reject/cancel above, so they share
                        // that exact floor.
                        .requestMatchers(HttpMethod.POST, "/api/v1/ax-renewal/projects/*/implementation/error-fix/start")
                        .hasAnyAuthority(AppRole.ADMIN.authority(), AppRole.DEVELOPER.exactAuthority())
                        .requestMatchers(HttpMethod.POST, "/api/v1/ax-renewal/projects/*/implementation/ui-change/start")
                        .hasAnyAuthority(AppRole.ADMIN.authority(), AppRole.DEVELOPER.exactAuthority())
                        .requestMatchers(HttpMethod.POST, "/api/v1/ax-renewal/projects/*/implementation/*/run-tests")
                        .hasAnyAuthority(AppRole.ADMIN.authority(), AppRole.DEVELOPER.exactAuthority())
                        // Revert writes to the real destination, exactly the
                        // class of action Approve is (ADR-044) — same floor.
                        .requestMatchers(HttpMethod.POST, "/api/v1/ax-renewal/projects/*/implementation/*/revert")
                        .hasAnyAuthority(AppRole.ADMIN.authority(), AppRole.DEVELOPER.exactAuthority())
                        // Run Locally start/stop (ADR-048) — this executes the
                        // destination's own npm scripts on the backend's
                        // machine, the same class of action as starting/
                        // approving/rejecting an implementation run, so it
                        // gets the same floor, not a lower one. Status is a
                        // plain read and falls through to the GET rule above.
                        .requestMatchers(HttpMethod.POST, "/api/v1/ax-renewal/projects/*/run-locally/start")
                        .hasAnyAuthority(AppRole.ADMIN.authority(), AppRole.DEVELOPER.exactAuthority())
                        .requestMatchers(HttpMethod.POST, "/api/v1/ax-renewal/projects/*/run-locally/stop")
                        .hasAnyAuthority(AppRole.ADMIN.authority(), AppRole.DEVELOPER.exactAuthority())
                        // AI Error Assistant, phase 1 (ADR-062) — read-only,
                        // same reasoning and same floor as assistant/chat
                        // above: it can point a local model at the
                        // destination to read it, but writes nothing, so it
                        // sits at the read floor, not Run Locally start/
                        // stop's write floor just above.
                        .requestMatchers(HttpMethod.POST, "/api/v1/ax-renewal/projects/*/run-locally/explain")
                        .hasRole(AppRole.ADMIN.name())

                        // --- Claude Usage, read side (ADR-035) ---------------
                        // Its own prefix rather than /api/v1/admin/**, for the
                        // same reason AX Renewal above has one: a plain `user`
                        // must reach their own row, and the generic admin floor
                        // below would refuse them before the controller ran.
                        //
                        // Not a hole in that floor. The response is scoped from
                        // the session inside ClaudeUsageService.dashboardFor --
                        // super_admin sees the team, everyone else (admin
                        // included, ADR-100) sees only themselves -- and there is
                        // no parameter on this route that could widen it. The
                        // roster mutations stay under /api/v1/admin/claude-usage
                        // at the super_admin floor above.
                        // // req: FR-USAGE-001, NFR-SEC-PII
                        .requestMatchers(HttpMethod.GET, "/api/v1/claude-usage")
                        .hasRole(AppRole.USER.name())

                        // --- My Attendance, read side (ADR-086) --------------
                        // Its own prefix rather than /api/v1/admin/**, same
                        // reason as Claude Usage just above: `user` and
                        // `developer` are blocked from the admin app entirely
                        // now (ADR-086), so this is the one place either can
                        // still check their own clock-in.
                        //
                        // Not a hole in that floor. The response is scoped
                        // from the session inside
                        // HrmsNotificationService.myAttendanceFor -- every
                        // caller sees only the row naming them, and there is
                        // no parameter on this route that could widen it. The
                        // admin attendance.reports route stays unscoped and
                        // super_admin only, above (ADR-100).
                        // // req: FR-OPS-001, NFR-SEC-PII
                        .requestMatchers(HttpMethod.GET, "/api/v1/me/attendance")
                        .hasRole(AppRole.USER.name())

                        // --- My Security, read side (ADR-093) ----------------
                        // Same shape as My Attendance just above: own prefix,
                        // USER floor, scoped from the session inside
                        // SessionService.recentSignIns -- no parameter here
                        // widens it.
                        // // req: FR-OPS-001, NFR-SEC-PII, ADR-093
                        .requestMatchers(HttpMethod.GET, "/api/v1/me/security")
                        .hasRole(AppRole.USER.name())

                        // --- Announcements, read side (ADR-098) ---------------
                        // Its own prefix rather than /api/v1/admin/**: every
                        // signed-in role reads announcements, and the admin floor
                        // below would refuse a plain `user`. GET only -- the
                        // writes live under /api/v1/admin/announcements and keep
                        // the admin floor. Not a hole in it: the service returns
                        // only published, unexpired announcements and answers 404
                        // for a draft, so nothing an admin has in progress leaks.
                        // // req: ADR-098
                        .requestMatchers(HttpMethod.GET, "/api/v1/announcements", "/api/v1/announcements/*")
                        .hasRole(AppRole.USER.name())

                        // --- Store, read side (ADR-108) -----------------------
                        // Same shape as Announcements just above: every
                        // signed-in role browses and downloads, GET only, and
                        // the writes live under /api/v1/admin/store at the
                        // admin floor. Not a hole in it: StoreService lists
                        // public apps only and answers 404 for a private app's
                        // build unless the caller is an admin.
                        // // req: ADR-108
                        .requestMatchers(HttpMethod.GET, "/api/v1/store/**")
                        .hasRole(AppRole.USER.name())

                        // --- Legal pages (Terms of Service / Privacy Policy) -
                        // Stated here, ahead of the general /admin/** floor
                        // below, the same way the super_admin-only prefixes
                        // above are: an admin may open this page and read it
                        // (falls through to /admin/** = ADMIN), but every
                        // write is narrowed to super_admin specifically —
                        // the product decision in this feature's own task,
                        // not something /admin/**'s default would give.
                        .requestMatchers(HttpMethod.PUT, "/api/v1/admin/legal/**")
                        .hasRole(AppRole.SUPER_ADMIN.name())
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/legal/**")
                        .hasRole(AppRole.SUPER_ADMIN.name())

                        // --- Sidebar kebab menu: delete is super_admin only --
                        // Stated here ahead of the general /admin/** floor, same
                        // pattern as Legal pages above: rename/move (PATCH items,
                        // POST/PUT groups) fall through to the admin floor below,
                        // but deleting a product or a custom group is narrowed to
                        // super_admin specifically (task spec: "Delete endpoints
                        // ... super_admin only; 403 for admin"). This also
                        // tightens product delete, previously admin-reachable
                        // under the general floor alone.
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/admin/nav/products/**")
                        .hasRole(AppRole.SUPER_ADMIN.name())
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/admin/nav/groups/**")
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

                        // /terms and /privacy are landing-site pages, open to a
                        // signed-out visitor the same way /docs-content is above
                        // — deliberately below the `user` floor /api/v1/pages
                        // carries, not an oversight of it.
                        .requestMatchers(HttpMethod.GET, "/api/v1/legal/**").permitAll()

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
