package com.coc.docs.web;

import com.coc.docs.auth.CurrentUser;
import com.coc.docs.content.Locale;
import com.coc.docs.page.PageService;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The reader path: sections, navigation, and published pages.
 *
 * <p>Read-only. Every route here sits behind a {@code user} floor set in
 * {@link com.coc.docs.config.SecurityConfig} — the MVP has no guest reading
 * (FR-PUBLIC-001 is Won't-have), and lowering specific pages is a deliberate
 * phase-2 decision under BR-026 rather than something that happens by default.
 *
 * <p>// req: FR-READ-001, FR-READ-003, FR-NAV-001, FR-NAV-005
 */
@RestController
@RequestMapping("/api/v1")
public class ContentController {

    private final PageService content;

    public ContentController(PageService content) {
        this.content = content;
    }

    /**
     * All sections, with their {@code dbBacked} flag.
     *
     * <p>The flag is what lets the two content sources coexist: the frontend
     * renders a section from this API when it is true and from
     * {@code content/*.tsx} when it is false. Sprint 1 flips it for
     * {@code jex2.0} alone, and flipping it back is the rollback.
     *
     * <p>// req: FR-NAV-005, CONST-OPS-03
     */
    @GetMapping("/sections")
    List<ApiDtos.SectionResponse> sections() {
        return content.listSections().stream().map(ApiDtos.SectionResponse::of).toList();
    }

    /**
     * Navigation for one section, in one locale.
     *
     * <p>Filtered to what this caller may open — see
     * {@link PageService#navigation}. The caller comes from the security context,
     * never from the request, so there is nothing here to spoof.
     *
     * <p>// req: FR-NAV-001, FR-AUTH-006
     */
    @GetMapping("/sections/{sectionSlug}/pages")
    List<ApiDtos.NavEntryResponse> navigation(
            @PathVariable String sectionSlug,
            @RequestParam(defaultValue = "en") String locale,
            @AuthenticationPrincipal CurrentUser caller) {

        return content.navigation(sectionSlug, parseLocale(locale), caller).stream()
                .map(ApiDtos.NavEntryResponse::of)
                .toList();
    }

    /**
     * One published page.
     *
     * <p>Three outcomes: the page, a 301 when the slug has been renamed, or a
     * 404. The 301 is the whole reason {@code page_slug_history} exists — an
     * inbound bookmark or an external link has to keep working across a rename,
     * or renaming becomes something nobody dares do (CONST-OPS-02).
     *
     * <p>A fourth outcome is 403: the page exists but sits above the caller's
     * role (FR-AUTH-006). That check is in the service, not here — a reader
     * calling this route directly with curl gets the same answer as one who
     * loaded the UI, which is the only kind of check that counts (RISK-019).
     *
     * <p>// req: FR-READ-001, FR-READ-003, FR-AUTH-006
     */
    @GetMapping("/pages/{sectionSlug}/{pageSlug}")
    ResponseEntity<?> page(
            @PathVariable String sectionSlug,
            @PathVariable String pageSlug,
            @RequestParam(defaultValue = "en") String locale,
            @AuthenticationPrincipal CurrentUser caller) {

        Locale parsed = parseLocale(locale);
        PageService.PageLookup lookup = content.readPublished(sectionSlug, pageSlug, parsed, caller);

        if (lookup.isMoved()) {
            PageService.SlugMoved moved = lookup.moved();
            URI canonical = URI.create(
                    "/api/v1/pages/" + moved.sectionSlug() + "/" + moved.canonicalSlug()
                            + "?locale=" + parsed.wire());
            return ResponseEntity.status(301)
                    .location(canonical)
                    .body(new ApiDtos.MovedResponse(moved.sectionSlug(), moved.canonicalSlug()));
        }

        return ResponseEntity.ok(ApiDtos.PageResponse.of(lookup.found()));
    }

    /**
     * Parses the locale query parameter.
     *
     * <p>An unrecognised value is a 400 naming the three supported locales, not a
     * silent fall back to English. Falling back would mean a typo produced a page
     * in the wrong language with no indication anything had gone wrong.
     */
    static Locale parseLocale(String raw) {
        return Locale.parse(raw)
                .orElseThrow(() -> ApiException.badRequest(
                        "unsupported locale \"" + raw + "\"; expected en, km or ko"));
    }
}
