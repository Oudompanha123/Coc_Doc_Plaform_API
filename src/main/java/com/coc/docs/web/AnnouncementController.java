package com.coc.docs.web;

import com.coc.docs.announcement.AnnouncementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Announcements as the user site reads them (ADR-098).
 *
 * <p>Its own prefix rather than under {@code /api/v1/admin/**}, because every
 * signed-in role reads announcements and the admin floor would refuse a plain
 * {@code user}. Read-only, and it only ever returns what is published and
 * unexpired — {@link AnnouncementService#liveForReader} answers 404 for
 * anything else, drafts included. The floor is one line in
 * {@code SecurityConfig}, per ADR-011.
 *
 * <p>// req: ADR-098
 */
@RestController
@RequestMapping("/api/v1/announcements")
@Tag(name = "Announcements", description = "Admin-authored notices for every signed-in user")
public class AnnouncementController {

    private final AnnouncementService announcements;

    public AnnouncementController(AnnouncementService announcements) {
        this.announcements = announcements;
    }

    @Operation(summary = "Live announcements, most recently published first")
    @GetMapping
    List<ApiDtos.AnnouncementResponse> list() {
        return announcements.liveForReaders().stream().map(ApiDtos.AnnouncementResponse::of).toList();
    }

    @Operation(
            summary = "One live announcement",
            description = "404 for an unknown, draft, unpublished or expired announcement alike.")
    @GetMapping("/{id}")
    ApiDtos.AnnouncementResponse get(@PathVariable UUID id) {
        return ApiDtos.AnnouncementResponse.of(announcements.liveForReader(id));
    }
}
