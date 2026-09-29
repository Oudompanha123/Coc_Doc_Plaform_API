package com.coc.docs.web;

import com.coc.docs.notify.HrmsNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin Attendance page's own surface: every HRMS report recorded for one
 * calendar date, across however many departments HRMS sent that day.
 *
 * <p>Distinct from {@link NotificationFeedController}'s {@code /hrms/{id}}
 * route, which is the same underlying report but reachable by any signed-in
 * role and only one at a time (whichever a person's own activity feed already
 * names). This route is the opposite shape on purpose: every department for a
 * date, so the page's date+department picker has something to populate itself
 * from before a person has picked anything — and it is {@code admin}-gated
 * because it is no longer "my own feed", it is every department's attendance.
 *
 * <p>Sits under {@code /api/v1/admin/**}, already covered by {@code
 * SecurityConfig}'s generic {@code admin}-role rule for that prefix, the same
 * as {@link AdminClaudeUsageController} — no floor above the one every other
 * editorial admin action already carries, and no {@code @PreAuthorize}
 * (ADR-011: one enforcement point).
 *
 * <p>Nothing here is stored, same as the route it shares its data with — see
 * {@link HrmsNotificationService#reportsForDate} and ADR-042.
 */
@RestController
@RequestMapping("/api/v1/admin/attendance")
public class AdminAttendanceController {

    private final HrmsNotificationService hrms;

    public AdminAttendanceController(HrmsNotificationService hrms) {
        this.hrms = hrms;
    }

    /**
     * Every department's report for one date, fetched from the mailbox on
     * this request — one round trip per department, never cached or stored.
     *
     * @param date the calendar date to look up, {@code yyyy-MM-dd}
     */
    @Operation(
            summary = "Every HRMS attendance report for one date",
            description = "One entry per department HRMS sent a report for on that date. "
                    + "Fetched from the mailbox on the request and never stored.")
    @GetMapping("/reports")
    List<ApiDtos.HrmsMailContentResponse> reportsForDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return hrms.reportsForDate(date).stream().map(ApiDtos.HrmsMailContentResponse::of).toList();
    }
}
