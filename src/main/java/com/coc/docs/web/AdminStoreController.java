package com.coc.docs.web;

import com.coc.docs.auth.CurrentUser;
import com.coc.docs.store.StoreService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.DataInputStream;
import java.io.EOFException;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Store management (ADR-108, ADR-111): adding and editing apps, their logos,
 * and uploading or removing their builds ("Next version").
 *
 * <p>Sits under {@code /api/v1/admin/**}, so {@code SecurityConfig}'s admin
 * floor for that prefix covers it — no {@code @PreAuthorize}, per ADR-011's
 * single enforcement point.
 *
 * <p><b>Files arrive in the request body, not multipart.</b> An installer can
 * be hundreds of megabytes; the body streams straight to disk through
 * {@code StoreFileStorage}, where the Store's own size cap applies, rather
 * than through the servlet container's multipart parser and its separate,
 * deployment-wide limit (sized for avatars).
 *
 * <p>// req: ADR-108, ADR-111, NFR-OPS-AUDIT
 */
@RestController
@RequestMapping("/api/v1/admin/store/apps")
@Tag(name = "Store (admin)", description = "Manage the Store's apps and upload their builds")
public class AdminStoreController {

    /** The JSON header of a build upload holds a version, two flags and up to 1500 characters of notes. */
    static final int MAX_HEADER_BYTES = 16 * 1024;

    private final StoreService store;
    private final ObjectMapper json;

    public AdminStoreController(StoreService store, ObjectMapper json) {
        this.store = store;
        this.json = json;
    }

    @Operation(summary = "Every app, public and private, with its builds newest first")
    @GetMapping
    List<StoreDtos.AppResponse> list() {
        return store.fullCatalog().stream().map(StoreDtos.AppResponse::of).toList();
    }

    @Operation(summary = "Add an app", description = "Name, description and visibility. 409 if the name is taken.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    StoreDtos.AppResponse create(
            @Valid @RequestBody StoreDtos.AppRequest request, @AuthenticationPrincipal CurrentUser caller) {
        return StoreDtos.AppResponse.of(
                store.createApp(request.name(), request.description(), request.category(), request.isPublic(), caller));
    }

    @Operation(summary = "Edit an app's name, description or visibility")
    @PutMapping("/{id}")
    StoreDtos.AppResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody StoreDtos.AppRequest request,
            @AuthenticationPrincipal CurrentUser caller) {
        return StoreDtos.AppResponse.of(
                store.updateApp(id, request.name(), request.description(), request.category(), request.isPublic(), caller));
    }

    @Operation(summary = "Delete an app, every build of it, and their files")
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal CurrentUser caller) {
        store.deleteApp(id, caller);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Upload or replace an app's icon",
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

    /**
     * Uploads the next version of an app. The body is a 4-byte big-endian
     * length, then that many bytes of UTF-8 JSON ({@link
     * StoreDtos.BuildUploadHeader}), then the file's bytes to the end.
     *
     * <p>Why a header in the body: the "What's new" notes run to 1500
     * characters, which percent-encoded Korean or Khmer would push past the
     * request-line limit in a query string; and a multipart body would put the
     * file under the deployment-wide multipart limit. This keeps one request,
     * no size limit but the Store's own, and the file still streams straight
     * to disk.
     */
    @Operation(
            summary = "Upload the next version",
            description = "Body: 4-byte big-endian length, UTF-8 JSON {version, environments, notes, fileName}, then "
                    + "the file. The platform is the file's type. 409 if that platform and version already has a "
                    + "build in a chosen environment.")
    @PostMapping("/{id}/builds")
    @ResponseStatus(HttpStatus.CREATED)
    StoreDtos.AppResponse uploadBuild(
            @PathVariable UUID id, HttpServletRequest request, @AuthenticationPrincipal CurrentUser caller)
            throws IOException {
        try (InputStream body = request.getInputStream()) {
            StoreDtos.BuildUploadHeader header = readHeader(body);
            return StoreDtos.AppResponse.of(store.uploadBuild(
                    id, header.version(), header.environments(), header.notes(), header.fileName(), body, caller));
        }
    }

    private StoreDtos.BuildUploadHeader readHeader(InputStream body) throws IOException {
        DataInputStream in = new DataInputStream(body);
        int length;
        byte[] bytes;
        try {
            length = in.readInt();
            if (length <= 0 || length > MAX_HEADER_BYTES) {
                throw ApiException.badRequest("the upload's header is missing or too large");
            }
            bytes = in.readNBytes(length);
        } catch (EOFException e) {
            throw ApiException.badRequest("the upload's header is incomplete");
        }
        if (bytes.length != length) {
            throw ApiException.badRequest("the upload's header is incomplete");
        }
        try {
            return json.readValue(bytes, StoreDtos.BuildUploadHeader.class);
        } catch (IOException e) {
            throw ApiException.badRequest("the upload's header is not valid JSON");
        }
    }

    @Operation(summary = "Remove one build", description = "Its file is deleted; it stays in the history as Removed.")
    @DeleteMapping("/{id}/builds/{buildId}")
    StoreDtos.AppResponse removeBuild(
            @PathVariable UUID id, @PathVariable UUID buildId, @AuthenticationPrincipal CurrentUser caller) {
        return StoreDtos.AppResponse.of(store.removeBuild(id, buildId, caller));
    }
}
