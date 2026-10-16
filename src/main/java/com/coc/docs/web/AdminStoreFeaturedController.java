package com.coc.docs.web;

import com.coc.docs.auth.CurrentUser;
import com.coc.docs.store.StoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Which app the Store page's banner features (ADR-117). Its own controller,
 * not a route on {@link AdminStoreController}: that one is mapped to
 * {@code /apps}, where {@code /apps/featured} would read as an app id.
 *
 * <p>Under {@code /api/v1/admin/**}, so the admin floor in
 * {@code SecurityConfig} covers it (ADR-011's single enforcement point).
 *
 * <p>// req: ADR-117, NFR-OPS-AUDIT
 */
@RestController
@RequestMapping("/api/v1/admin/store/featured")
@Tag(name = "Store (admin)", description = "Manage the Store's apps and upload their builds")
public class AdminStoreFeaturedController {

    private final StoreService store;

    public AdminStoreFeaturedController(StoreService store) {
        this.store = store;
    }

    @Operation(
            summary = "Choose the app the Store banner features",
            description = "{appId: <uuid>} pins that app; {appId: null} goes back to the latest upload or update. "
                    + "404 for an unknown app.")
    @PutMapping
    StoreDtos.FeaturedResponse set(
            @RequestBody StoreDtos.FeaturedRequest request, @AuthenticationPrincipal CurrentUser caller) {
        return new StoreDtos.FeaturedResponse(store.setFeaturedApp(request.appId(), caller).orElse(null));
    }
}
