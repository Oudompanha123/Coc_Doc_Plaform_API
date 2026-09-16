package com.coc.docs.web;

import com.coc.docs.auth.CurrentUser;
import com.coc.docs.notify.ActivityDigestService;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * On-demand sending of the content-activity digest.
 *
 * <p>The digest normally fires on its own schedule; this exists for the days
 * it did not — a deployment that was down at 08:00, a recipient list that was
 * wrong, a change to the report someone wants to see before tomorrow.
 *
 * <p><b>This route sits at the {@code super_admin} floor, not the {@code admin}
 * floor the rest of {@code /api/v1/admin/**} carries.</b> Two reasons, both
 * recorded in {@code SecurityConfig} beside the rule itself: the response and
 * the mail it sends carry page titles and the names of the people who edited
 * them (NFR-SEC-PII), and the act itself puts mail in other people's inboxes.
 * Neither is editorial work, which is what the {@code admin} floor is for.
 *
 * <p>No {@code @PreAuthorize} here, matching {@link AdminContentController}:
 * ADR-011 chose one enforcement point so that "is every route covered" is
 * answerable by reading one file.
 *
 * <p>// req: FR-OPS-001, FR-AUTH-008, NFR-SEC-PII, NFR-OPS-AUDIT
 */
@RestController
@RequestMapping("/api/v1/admin/notifications")
public class AdminNotificationController {

    private final ActivityDigestService digest;

    public AdminNotificationController(ActivityDigestService digest) {
        this.digest = digest;
    }

    /**
     * Sends the content-activity digest for one day.
     *
     * <p>A POST, and not idempotent: calling it twice sends it twice, which is
     * the intended behaviour for "send it again" and the reason it is not a
     * GET that something might prefetch.
     *
     * @param day the day to report on, {@code YYYY-MM-DD} in the configured
     *     zone. Omitted means yesterday — the same day the scheduled run would
     *     have covered, resolved by the same method so the two cannot diverge.
     */
    @Operation(
            summary = "Send the content-activity digest for a day",
            description = "Omitting `day` reports on yesterday. super_admin only: the mail and the "
                    + "response carry page titles and editor names.")
    @PostMapping("/digest")
    ApiDtos.DigestResponse sendDigest(
            @RequestParam(required = false) String day, @AuthenticationPrincipal CurrentUser caller) {

        LocalDate yesterday = digest.yesterday();
        LocalDate target = day == null || day.isBlank() ? yesterday : parseDay(day);

        // A future day cannot have activity, so asking for one is a mistake —
        // most often a timezone slip or an off-by-one in whatever built the
        // date. Answering 200 with an empty report would hide that. Today is
        // allowed, and simply covers the hours so far.
        if (target.isAfter(yesterday.plusDays(1))) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "cannot send a digest for " + target + ", which is in the future");
        }

        return ApiDtos.DigestResponse.of(digest.sendDigestFor(target, caller));
    }

    /**
     * Parses the {@code day} parameter, refusing a malformed one with 400.
     *
     * <p>Taken as a {@code String} and parsed here rather than bound straight
     * to a {@link LocalDate}. Spring's binding failure is a
     * {@code MethodArgumentTypeMismatchException}, which
     * {@link ApiExceptionHandler}'s catch-all turns into <b>500 "an unexpected
     * error occurred"</b> — and a typo in a hand-written query parameter is not
     * a server fault, nor something a caller can act on when told that. This
     * route is the first in the API to take a date this way, so the choice is
     * made here rather than by widening the shared handler, which would change
     * the answer for every existing route at the same time.
     */
    private static LocalDate parseDay(String day) {
        try {
            return LocalDate.parse(day.trim());
        } catch (DateTimeParseException e) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "day must be a date in YYYY-MM-DD form, not '" + day + "'");
        }
    }
}
