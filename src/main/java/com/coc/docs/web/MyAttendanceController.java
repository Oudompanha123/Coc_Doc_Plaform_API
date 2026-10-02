package com.coc.docs.web;

import com.coc.docs.auth.CurrentUser;
import com.coc.docs.notify.HrmsNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The landing page's own personal Attendance surface — one calendar date,
 * narrowed to the caller's own row only (ADR-086).
 *
 * <p>Distinct from {@link AdminAttendanceController}'s {@code
 * /admin/attendance/reports}, which is the opposite shape on purpose (every
 * department, unscoped, {@code admin}-gated) and from {@link
 * NotificationFeedController}'s {@code /hrms/{id}}, which is the same kind of
 * per-caller scoping but keyed by a specific report id already named in the
 * caller's own activity feed, not by a date they pick freely. This route
 * exists so {@code user} and {@code developer} — blocked from the admin app
 * entirely as of ADR-086 — still have somewhere to check their own clock-in.
 *
 * <p>Floored at {@code USER} in {@code SecurityConfig}, above the generic
 * {@code /api/v1/admin/**} rule, the same pattern {@code
 * /api/v1/claude-usage} uses: no parameter on this route widens it, scope
 * comes from the session alone (TM-025), and {@link
 * HrmsNotificationService#myAttendanceFor} is where that narrowing actually
 * happens, not here.
 *
 * <p>No {@code @PreAuthorize}: ADR-011 keeps one enforcement point.
 */
@RestController
@RequestMapping("/api/v1/me/attendance")
public class MyAttendanceController {

    private final HrmsNotificationService hrms;

    public MyAttendanceController(HrmsNotificationService hrms) {
        this.hrms = hrms;
    }

    /**
     * The caller's own attendance row for one date.
     *
     * <p>Always 200: a day with nothing recorded, or an account with no HRMS
     * link at all, are both ordinary outcomes the response already describes
     * via {@code viewerLinked} and an empty {@code rows}, not failures worth a
     * 404. The screen tells the two apart from those fields, same as it
     * already does for {@code /notifications/hrms/{id}}.
     *
     * @param date the calendar date to look up, {@code yyyy-MM-dd}
     */
    @Operation(
            summary = "The caller's own HRMS attendance row for one date",
            description = "Scoped to the caller alone, matched on account.hrms_name or email — never another "
                    + "employee's row. viewerLinked is false when the account carries no HRMS link at all; "
                    + "an empty rows list with viewerLinked true means linked, but nothing recorded for this "
                    + "caller on this date.")
    @GetMapping
    ApiDtos.HrmsMailContentResponse myAttendance(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal CurrentUser caller) {
        return ApiDtos.HrmsMailContentResponse.of(hrms.myAttendanceFor(date, caller));
    }
}
