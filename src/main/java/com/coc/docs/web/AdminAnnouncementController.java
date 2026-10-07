package com.coc.docs.web;

import com.coc.docs.announcement.AnnouncementService;
import com.coc.docs.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Announcement management — the Administration sidebar's Announcements page
 * (ADR-098).
 *
 * <p>Sits under {@code /api/v1/admin/**}, so {@code SecurityConfig}'s
 * {@code admin} floor for that prefix already covers it: writing an
 * announcement is editorial work, the same kind an admin does when publishing
 * a page. No {@code @PreAuthorize}, per ADR-011's single enforcement point.
 *
 * <p>// req: ADR-098, NFR-OPS-AUDIT
 */
@RestController
@RequestMapping("/api/v1/admin/announcements")
@Tag(name = "Announcements", description = "Admin-authored notices for every signed-in user")
public class AdminAnnouncementController {

    private final AnnouncementService announcements;

    public AdminAnnouncementController(AnnouncementService announcements) {
        this.announcements = announcements;
    }

    private ApiDtos.AdminAnnouncementResponse toResponse(com.coc.docs.announcement.Announcement a) {
        return ApiDtos.AdminAnnouncementResponse.of(a, announcements.isExpired(a));
    }

    @Operation(summary = "Every announcement in every state, newest first")
    @GetMapping
    List<ApiDtos.AdminAnnouncementResponse> list() {
        return announcements.listAll().stream().map(this::toResponse).toList();
    }

    @Operation(summary = "One announcement, in any state")
    @GetMapping("/{id}")
    ApiDtos.AdminAnnouncementResponse get(@PathVariable UUID id) {
        return toResponse(announcements.get(id));
    }

    @Operation(
            summary = "Save a new announcement as a draft",
            description = "Nobody is notified until it is published.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiDtos.AdminAnnouncementResponse create(
            @Valid @RequestBody ApiDtos.AnnouncementRequest request, @AuthenticationPrincipal CurrentUser caller) {
        return toResponse(announcements.create(request.toDraft(), caller));
    }

    @Operation(
            summary = "Edit an announcement",
            description = "409 if versionNo is not the current one. Editing a published announcement "
                    + "takes effect for readers at once.")
    @PutMapping("/{id}")
    ApiDtos.AdminAnnouncementResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody ApiDtos.UpdateAnnouncementRequest request,
            @AuthenticationPrincipal CurrentUser caller) {
        return toResponse(announcements.update(id, request.versionNo(), request.toDraft(), caller));
    }

    @Operation(
            summary = "Publish an announcement",
            description = "Notifies every active account except the caller. 409 if already published.")
    @PostMapping("/{id}/publish")
    ApiDtos.AdminAnnouncementResponse publish(@PathVariable UUID id, @AuthenticationPrincipal CurrentUser caller) {
        return toResponse(announcements.publish(id, caller));
    }

    @Operation(
            summary = "Unpublish an announcement",
            description = "Hides it from readers and removes it from every bell. 409 if not published.")
    @PostMapping("/{id}/unpublish")
    ApiDtos.AdminAnnouncementResponse unpublish(@PathVariable UUID id, @AuthenticationPrincipal CurrentUser caller) {
        return toResponse(announcements.unpublish(id, caller));
    }

    @Operation(summary = "Delete an announcement in any state, and remove it from every bell")
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal CurrentUser caller) {
        announcements.delete(id, caller);
        return ResponseEntity.noContent().build();
    }
}
