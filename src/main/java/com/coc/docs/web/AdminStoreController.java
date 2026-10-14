package com.coc.docs.web;

import com.coc.docs.auth.CurrentUser;
import com.coc.docs.store.StoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Store management (ADR-108): registering apps and uploading their builds.
 *
 * <p>Sits under {@code /api/v1/admin/**}, so {@code SecurityConfig}'s admin
 * floor for that prefix covers it — no {@code @PreAuthorize}, per ADR-011's
 * single enforcement point.
 *
 * <p><b>Files arrive as the raw request body, not multipart.</b> An installer
 * can be hundreds of megabytes; a raw body streams straight to disk through
 * {@code StoreFileStorage}, where the Store's own size cap applies, rather
 * than through the servlet container's multipart parser and its separate,
 * deployment-wide limit (which is sized for avatars). The metadata travels in
 * the query string.
 *
 * <p>// req: ADR-108, NFR-OPS-AUDIT
 */
@RestController
@RequestMapping("/api/v1/admin/store/apps")
@Tag(name = "Store (admin)", description = "Register apps and upload their builds")
public class AdminStoreController {

    private final StoreService store;

    public AdminStoreController(StoreService store) {
        this.store = store;
    }

    @Operation(summary = "Every app, public and private, with its builds newest first")
    @GetMapping
    List<StoreDtos.AppResponse> list() {
        return store.fullCatalog().stream().map(StoreDtos.AppResponse::of).toList();
    }

    @Operation(summary = "Register an app", description = "The platform is fixed once set.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    StoreDtos.AppResponse create(
            @Valid @RequestBody StoreDtos.AppRequest request, @AuthenticationPrincipal CurrentUser caller) {
        return StoreDtos.AppResponse.of(store.createApp(
                request.name(), request.description(), request.platform(), request.isPublic(), caller));
    }

    @Operation(summary = "Edit an app's name, description or visibility")
    @PutMapping("/{id}")
    StoreDtos.AppResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody StoreDtos.AppRequest request,
            @AuthenticationPrincipal CurrentUser caller) {
        return StoreDtos.AppResponse.of(
                store.updateApp(id, request.name(), request.description(), request.isPublic(), caller));
    }

    @Operation(summary = "Delete an app, every build of it, and their files")
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal CurrentUser caller) {
        store.deleteApp(id, caller);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Upload or replace an app's logo",
            description = "Body: the image bytes. Content-Type image/png, image/jpeg or image/webp; 2 MB at most.")
    @PutMapping("/{id}/logo")
    StoreDtos.AppResponse logo(
            @PathVariable UUID id,
            @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType,
            HttpServletRequest request,
            @AuthenticationPrincipal CurrentUser caller) throws IOException {
        try (InputStream body = request.getInputStream()) {
            return StoreDtos.AppResponse.of(store.uploadLogo(id, contentType, body, caller));
        }
    }

    @Operation(
            summary = "Upload a build",
            description = "Body: the install file's bytes (application/octet-stream). 409 if the app already has a "
                    + "build of this version in this environment.")
    @PostMapping("/{id}/releases")
    @ResponseStatus(HttpStatus.CREATED)
    StoreDtos.AppResponse upload(
            @PathVariable UUID id,
            @RequestParam String version,
            @RequestParam String environment,
            @RequestParam String fileName,
            @RequestParam(required = false, defaultValue = "") String notes,
            HttpServletRequest request,
            @AuthenticationPrincipal CurrentUser caller) throws IOException {
        try (InputStream body = request.getInputStream()) {
            return StoreDtos.AppResponse.of(
                    store.uploadRelease(id, version, environment, notes, fileName, body, caller));
        }
    }

    @Operation(summary = "Delete one build and its file")
    @DeleteMapping("/{id}/releases/{releaseId}")
    StoreDtos.AppResponse deleteRelease(
            @PathVariable UUID id, @PathVariable UUID releaseId, @AuthenticationPrincipal CurrentUser caller) {
        return StoreDtos.AppResponse.of(store.deleteRelease(id, releaseId, caller));
    }
}
