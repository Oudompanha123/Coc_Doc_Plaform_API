package com.coc.docs.web;

import com.coc.docs.auth.AuthenticationFailedException;
import com.coc.docs.auth.EmailAlreadyRegisteredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** One error shape, project-wide. Every module's controller advice work funnels through here. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<ApiError> handleAuthenticationFailed(AuthenticationFailedException e) {
        // Deliberately generic, and deliberately NOT e.getMessage()/e.reason():
        // UC-004 E-3 forbids the response revealing *why* a sign-in was
        // refused (bad token vs. no account vs. deactivated/pending/rejected).
        // The caller already wrote the specific reason to the audit log
        // before this exception was thrown; it must never also reach the
        // HTTP body.
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiError("Sign-in failed"));
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<ApiError> handleEmailAlreadyRegistered(EmailAlreadyRegisteredException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(e.getMessage()));
    }

    @ExceptionHandler(AdminActionException.class)
    public ResponseEntity<ApiError> handleAdminAction(AdminActionException e) {
        return ResponseEntity.status(e.status()).body(new ApiError(e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(new ApiError("Invalid request"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.internalServerError().body(new ApiError("Unexpected error"));
    }
}
