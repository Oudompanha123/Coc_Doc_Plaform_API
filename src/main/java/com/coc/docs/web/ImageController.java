package com.coc.docs.web;

import com.coc.docs.account.AvatarStorage;
import com.coc.docs.storage.LocalAvatarStorage;
import io.swagger.v3.oas.annotations.Operation;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves a profile photo kept on this backend's own disk (ADR-119) — the
 * local-disk counterpart of {@code AvatarController}.
 *
 * <p>Unauthenticated for the reason given there: an {@code <img>} does not
 * carry the session cookie across origins, so the protection is the random
 * UUID in the file name.
 *
 * <p>Only a name of exactly the shape {@link LocalAvatarStorage} writes is
 * looked up, so this cannot be walked out of the folder, and it never serves
 * the other things kept there (an AX Renewal source archive is a {@code .zip}).
 *
 * <p>// req: FR-AUTH-005, NFR-SEC-PII, ADR-119
 */
@RestController
public class ImageController {

    private final ObjectProvider<AvatarStorage> storage;

    public ImageController(ObjectProvider<AvatarStorage> storage) {
        this.storage = storage;
    }

    @Operation(summary = "A profile photo stored on the backend's disk", description = "Public, like the avatar route.")
    @GetMapping("/api/v1/image/{fileName}")
    ResponseEntity<Resource> image(@PathVariable String fileName) {
        if (!(storage.getIfAvailable() instanceof LocalAvatarStorage local)) {
            return ResponseEntity.notFound().build();
        }
        Optional<Path> file = local.find(fileName);
        if (file.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(contentType(fileName))
                // The name changes with every upload, so a given URL's bytes never do.
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(file.get()));
    }

    private static MediaType contentType(String fileName) {
        if (fileName.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (fileName.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        return MediaType.IMAGE_JPEG;
    }
}
