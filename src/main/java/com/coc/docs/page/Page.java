package com.coc.docs.page;

import com.coc.docs.account.AppRole;
import java.time.Instant;
import java.util.UUID;

/**
 * A page: ONE thing that exists in three languages, not three pages. Locale
 * lives on {@link PageVersion}, never here.
 *
 * <p>{@code order} is the single authoritative ordering value, replacing three
 * hand-synchronised arrays that nothing but developer discipline kept in
 * agreement (FR-NAV-004).
 *
 * <p>// req: FR-NAV-004, FR-AUTH-006, FR-CONT-007
 */
public record Page(
        UUID id,
        UUID sectionId,
        String slug,
        int order,
        String badge,
        AppRole minRole,
        Instant deletedAt,
        Instant createdAt) {

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
