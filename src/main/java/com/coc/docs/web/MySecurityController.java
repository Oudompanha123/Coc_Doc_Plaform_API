package com.coc.docs.web;

import com.coc.docs.auth.CurrentUser;
import com.coc.docs.auth.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Settings page's Security card (ADR-093): sign-in method plus the
 * caller's own recent sign-in/sign-out history.
 *
 * <p>Own account only — {@code caller} is resolved from the session, never
 * from a request parameter (TM-025), the same shape {@link MyAttendanceController}
 * already uses. No {@code @PreAuthorize}: ADR-011 keeps one enforcement point.
 */
@RestController
@RequestMapping("/api/v1/me/security")
public class MySecurityController {

    /** How many sign-in/sign-out events the card shows. */
    private static final int RECENT_SIGN_INS_LIMIT = 10;

    private final SessionService sessions;

    public MySecurityController(SessionService sessions) {
        this.sessions = sessions;
    }

    @Operation(
            summary = "The caller's own sign-in method and recent sign-in history",
            description = "Google is the only sign-in method this app has ever had. recentSignIns carries no IP "
                    + "address at all — the audit row behind it never captured one, so there is nothing to mask.")
    @GetMapping
    ApiDtos.MySecurityResponse mySecurity(@AuthenticationPrincipal CurrentUser caller) {
        var recent = sessions.recentSignIns(caller, RECENT_SIGN_INS_LIMIT).stream()
                .map(e -> new ApiDtos.MySecurityResponse.SignInEventResponse(e.action(), e.occurredAt(), e.device()))
                .toList();
        return new ApiDtos.MySecurityResponse("google", caller.account().email(), recent);
    }
}
