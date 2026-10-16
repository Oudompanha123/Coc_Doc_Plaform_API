package com.coc.docs.web;

import com.coc.docs.auth.CurrentUser;
import com.coc.docs.store.StoreFiles;
import com.coc.docs.store.StoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Store page, as every signed-in user reads it (ADR-108, ADR-111).
 *
 * <p>{@code SecurityConfig} puts this prefix at the {@code user} floor, except
 * the logo, which is public for the same reason profile photos are
 * (ADR-101). Which apps and builds a caller may reach is
 * {@link StoreService}'s answer: public ones, plus private ones for an admin.
 *
 * <p>// req: ADR-108, ADR-111
 */
@RestController
@RequestMapping("/api/v1/store")
@Tag(name = "Store", description = "The team's apps and their downloadable builds")
public class StoreController {

    private final StoreService store;

    public StoreController(StoreService store) {
        this.store = store;
    }

    @Operation(summary = "Every public app, alphabetically, with its builds newest first")
    @GetMapping("/apps")
    List<StoreDtos.AppResponse> apps() {
        return store.publicCatalog().stream().map(StoreDtos.AppResponse::of).toList();
    }

    @Operation(
            summary = "One app, by the number in its page URL (/store-<number>)",
            description = "404 for a private app unless the caller is an admin.")
    @GetMapping("/apps/{number:\\d+}")
    StoreDtos.AppResponse app(@PathVariable long number, @AuthenticationPrincipal CurrentUser caller) {
        return StoreDtos.AppResponse.of(store.entryForReader(number, caller));
    }

    @Operation(
            summary = "Where to download one build from",
            description = "With S3 storage, a presigned URL that expires in minutes (the bytes then come straight "
                    + "from S3); with local storage, this API's own /download route. 404 for a removed build, and "
                    + "for a private app's build unless the caller is an admin.")
    @GetMapping("/builds/{id}/download-link")
    ResponseEntity<StoreDtos.DownloadLink> downloadLink(
            @PathVariable UUID id, @AuthenticationPrincipal CurrentUser caller) {
        StoreService.Download file = store.download(id, caller);
        StoreDtos.DownloadLink link = switch (file.delivery()) {
            case StoreFiles.Delivery.Presigned p ->
                    new StoreDtos.DownloadLink(p.url().toString(), true, p.expiresAt());
            case StoreFiles.Delivery.LocalFile ignored ->
                    new StoreDtos.DownloadLink("/api/v1/store/builds/" + id + "/download", false, null);
        };
        // A presigned URL is a bearer credential for its lifetime: never cache it.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(link);
    }

    @Operation(
            summary = "Download one build",
            description = "Always an attachment. With S3 storage this redirects to a presigned URL. 404 for a "
                    + "removed build, and for a private app's build unless the caller is an admin.")
    @GetMapping("/builds/{id}/download")
    ResponseEntity<Resource> download(@PathVariable UUID id, @AuthenticationPrincipal CurrentUser caller) {
        StoreService.Download file = store.download(id, caller);
        return switch (file.delivery()) {
            case StoreFiles.Delivery.Presigned p -> ResponseEntity.status(HttpStatus.FOUND)
                    .location(p.url())
                    .cacheControl(CacheControl.noStore())
                    .build();
            case StoreFiles.Delivery.LocalFile local -> ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(file.fileName(), StandardCharsets.UTF_8)
                            .build()
                            .toString())
                    .contentType(MediaType.parseMediaType(file.contentType()))
                    .cacheControl(CacheControl.noStore())
                    .body(new FileSystemResource(local.path()));
        };
    }

    @Operation(summary = "An app's logo image")
    @GetMapping("/apps/{id}/logo")
    ResponseEntity<Resource> logo(@PathVariable UUID id) {
        StoreService.Logo logo = store.logo(id);
        // The URL carries ?v=<updated time>, so a replaced logo is a new URL
        // and a long cache is safe.
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(logo.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)))
                .body(logo.body());
    }
}
