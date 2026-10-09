package com.coc.docs.web;

import com.coc.docs.content.ValidationIssue;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * An error the client is allowed to be told about.
 *
 * <p>Everything thrown deliberately by a service is one of these, with a status
 * chosen at the point the rule is known. The alternative -- letting a
 * {@code DataIntegrityViolationException} reach the web layer and guessing what
 * it meant -- turns a rule the schema enforces into a 500 the caller cannot act
 * on.
 *
 * <p>// req: NFR-USE-01
 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;

    /**
     * Transient because {@link List} is not serialisable and these never travel
     * anywhere that would serialise them -- they are read by
     * {@link ApiExceptionHandler} in the same thread that threw. Declared
     * explicitly rather than left to a warning suppression, so the reason is on
     * the record.
     */
    private final transient List<ValidationIssue> issues;

    /**
     * A stable, machine-readable reason, sent as the problem body's
     * {@code code} -- for a client that must act differently on two refusals
     * with the same status (the usage CLI telling "tracking disabled by an
     * administrator" from a bad credential, ADR-105). Null for most errors.
     */
    private final String code;

    public ApiException(HttpStatus status, String message) {
        this(status, message, List.of());
    }

    public ApiException(HttpStatus status, String message, List<ValidationIssue> issues) {
        this(status, message, issues, null);
    }

    private ApiException(HttpStatus status, String message, List<ValidationIssue> issues, String code) {
        super(message);
        this.status = status;
        this.issues = List.copyOf(issues);
        this.code = code;
    }

    /** As {@link #ApiException(HttpStatus, String)}, with a machine-readable {@link #code()}. */
    public static ApiException withCode(HttpStatus status, String code, String message) {
        return new ApiException(status, message, List.of(), code);
    }

    /** The machine-readable reason, or null. */
    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    /** Present only for a rejected body; empty otherwise. */
    public List<ValidationIssue> issues() {
        return issues;
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, what + " not found");
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * A body that failed the block-model validator.
     *
     * <p>422 rather than 400: the request was well-formed JSON that the server
     * understood and then refused on content grounds, and the distinction lets
     * the editor UI show per-block messages for one and a generic failure for
     * the other.
     */
    public static ApiException unprocessable(String message, List<ValidationIssue> issues) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message, issues);
    }

    /**
     * A concurrent edit. // req: FR-CONT-013
     */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }

    /** A missing or wrong bearer credential on a non-session route (e.g. {@code POST /api/ingest}). */
    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, message);
    }
}
