package com.coc.docs.web;

import com.coc.docs.auth.GoogleIdTokenVerifier;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns exceptions into RFC 9457 problem responses.
 *
 * <p>The dividing line: anything the caller can act on carries a message, and
 * anything else carries a generic one and is logged with a stack trace. A
 * server-side fault must not describe itself over the wire -- a SQL error text
 * or a class name is reconnaissance -- and an editor-facing rejection is
 * useless without the detail.
 *
 * <p>// req: NFR-USE-01, NFR-SEC-AUTH, FR-CONT-013
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApi(ApiException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        if (!e.issues().isEmpty()) {
            // The editor UI anchors each message to a block using these paths, so
            // they are structured rather than concatenated into the detail.
            problem.setProperty(
                    "issues",
                    e.issues().stream()
                            .map(issue -> Map.of("path", issue.path(), "message", issue.message()))
                            .toList());
        }
        return problem;
    }

    /**
     * A rejected credential.
     *
     * <p>One message for every cause. Which check failed -- expired, wrong
     * audience, unverified email, deactivated account -- is recorded in the audit
     * trail and told to nobody else. // req: TM-001
     */
    @ExceptionHandler(GoogleIdTokenVerifier.AuthenticationFailure.class)
    ProblemDetail handleAuthFailure(GoogleIdTokenVerifier.AuthenticationFailure e) {
        log.info("sign-in refused: {}", e.getMessage());
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "sign-in could not be completed");
    }

    /**
     * An authenticated caller whose role is insufficient.
     *
     * <p>403, not 404. Hiding the existence of an admin route from an
     * authenticated user buys nothing -- the route list is in the frontend
     * bundle -- and would make a legitimate permissions problem look like a bug.
     */
    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException e) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "your role does not permit this operation");
    }

    /** Bean-validation failures on a request body, reported per field. */
    @Override
    protected org.springframework.http.ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e,
            org.springframework.http.HttpHeaders headers,
            org.springframework.http.HttpStatusCode status,
            org.springframework.web.context.request.WebRequest request) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "the request body is not valid");
        problem.setProperty(
                "issues",
                e.getBindingResult().getFieldErrors().stream()
                        .map(error -> Map.of(
                                "path", error.getField(),
                                "message",
                                error.getDefaultMessage() == null
                                        ? "is not valid"
                                        : error.getDefaultMessage()))
                        .toList());
        return org.springframework.http.ResponseEntity.badRequest().body(problem);
    }

    /**
     * Anything unanticipated.
     *
     * <p>Logged in full, reported as a bare 500. This handler existing is the
     * reason services throw {@link ApiException} deliberately: whatever reaches
     * here is a bug, and it says so.
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception e) {
        log.error("unhandled exception", e);
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "an unexpected error occurred");
    }

    /** Kept package-visible for the test that asserts no handler leaks a cause message. */
    static List<Class<?>> handledDeliberately() {
        return List.of(
                ApiException.class,
                GoogleIdTokenVerifier.AuthenticationFailure.class,
                AccessDeniedException.class);
    }
}
