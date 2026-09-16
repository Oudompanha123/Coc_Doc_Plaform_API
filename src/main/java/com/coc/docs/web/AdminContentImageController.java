package com.coc.docs.web;

import com.coc.docs.storage.ContentImageStorage;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Uploads an image for a docs-content field (a feature card's icon, so
 * far) and hands back its URL — the caller then puts that URL into the
 * field like any other value and saves through the existing {@code
 * PUT /api/v1/admin/docs-content/**} endpoint. Sits under {@code
 * /api/v1/admin/**}, already covered by {@code SecurityConfig}'s
 * admin-role rule for that prefix.
 *
 * <p>{@link ContentImageStorage} is resolved through an {@code
 * ObjectProvider}, not a required constructor dependency, because a
 * deployment with no object-storage credentials configured registers no
 * bean for it at all (see {@code ContentImageStorageConfig}) and must
 * still start and serve every other route.
 */
@RestController
@RequestMapping("/api/v1/admin/content-images")
public class AdminContentImageController {

    private final ObjectProvider<ContentImageStorage> storage;

    public AdminContentImageController(ObjectProvider<ContentImageStorage> storage) {
        this.storage = storage;
    }

    @PostMapping
    Map<String, String> upload(@RequestParam("file") MultipartFile file) {
        ContentImageStorage images = storage.getIfAvailable();
        if (images == null) {
            throw ApiException.badRequest("Image upload is not available on this deployment yet.");
        }
        return Map.of("url", images.upload(file));
    }
}
