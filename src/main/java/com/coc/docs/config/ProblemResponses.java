package com.coc.docs.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Turns the filter chain's two refusals into problem responses.
 *
 * <p><b>Why this exists at all.</b> Without it, Spring Security answers an
 * unauthenticated request to a protected route with 403, because there is no
 * entry point to say "authenticate first" and the anonymous caller simply fails
 * the rule. For a browser-driven API that is actively wrong: the frontend has to
 * tell "sign in" apart from "you are signed in and this is not for you". Given
 * only 403 it would send a reader who touched an admin route back to sign-in,
 * where they would sign in successfully and be bounced again — a loop with no
 * error anyone can read.
 *
 * <p>So the distinction is drawn deliberately:
 *
 * <ul>
 *   <li><b>401</b> — no usable session. The frontend offers sign-in.
 *   <li><b>403</b> — a resolved caller whose role is insufficient. The frontend
 *       says so and does not offer sign-in.
 * </ul>
 *
 * <p>Neither response names the route's required role. That would tell an
 * unauthorised caller exactly what to acquire.
 *
 * <p>// req: FR-AUTH-005, FR-AUTH-006, NFR-USE-01, ADR-011
 */
@Component
public class ProblemResponses implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper json;

    public ProblemResponses(ObjectMapper json) {
        this.json = json;
    }

    /** No usable session: 401. */
    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException)
            throws IOException {

        write(response, HttpStatus.UNAUTHORIZED, "authentication is required");
    }

    /** A resolved caller with an insufficient role: 403. */
    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException {

        write(response, HttpStatus.FORBIDDEN, "your role does not permit this operation");
    }

    private void write(HttpServletResponse response, HttpStatus status, String detail)
            throws IOException {

        // No WWW-Authenticate header. It is correct for a bearer-token API and
        // wrong here: a browser seeing it on a 401 pops a native credential
        // dialog, and this system has no password to type into one.
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        json.writeValue(response.getOutputStream(), problem);
    }
}
