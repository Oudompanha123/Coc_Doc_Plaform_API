package com.coc.docs.web;

import com.coc.docs.legal.LegalPageService;
import com.coc.docs.legal.LegalSlug;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public read for Terms of Service / Privacy Policy — what {@code
 * /terms} and {@code /privacy} fetch. Permitted to everyone, signed in or
 * not (the landing site's own legal pages), unlike the {@code user} floor on
 * {@link ContentController}'s reader routes — see {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/v1/legal")
public class LegalController {

    private final LegalPageService legal;

    public LegalController(LegalPageService legal) {
        this.legal = legal;
    }

    @GetMapping("/{slug}")
    ApiDtos.LegalPageResponse page(@PathVariable String slug) {
        return ApiDtos.LegalPageResponse.of(legal.readPublished(parseSlug(slug)));
    }

    static LegalSlug parseSlug(String raw) {
        return LegalSlug.parse(raw)
                .orElseThrow(() -> ApiException.badRequest(
                        "unknown legal page \"" + raw + "\"; expected terms or privacy"));
    }
}
