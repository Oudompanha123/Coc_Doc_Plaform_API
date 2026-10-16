package com.coc.docs.web;

import com.coc.docs.auth.CurrentUser;
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
            summary = "Download one build",
            description = "Always an attachment. 404 for a removed build, and for a private app's build unless the "
                    + "caller is an admin.")
    @GetMapping("/builds/{id}/download")
    ResponseEntity<Resource> download(@PathVariable UUID id, @AuthenticationPrincipal CurrentUser caller) {
        StoreService.Download file = store.download(id, caller);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .cacheControl(CacheControl.noStore())
                .body(new FileSystemResource(file.path()));
    }

    @Operation(summary = "An app's logo image")
    @GetMapping("/apps/{id}/logo")
    ResponseEntity<Resource> logo(@PathVariable UUID id) {
        StoreService.Download file = store.logo(id);
        // The URL carries ?v=<updated time>, so a replaced logo is a new URL
        // and a long cache is safe.
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)))
                .body(new FileSystemResource(file.path()));
    }
}
