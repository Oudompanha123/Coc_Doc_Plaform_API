package com.coc.docs.account;

import java.time.Instant;
import java.util.UUID;

/**
 * An account row.
 *
 * <p>No password field, by design: authentication is Google OIDC and the system
 * stores no credentials (FR-AUTH-002). {@code deactivatedAt} rather than a
 * delete, so authored content and audit entries keep their attribution after
 * someone leaves (FR-AUTH-003).
 *
 * <p>// req: FR-AUTH-002, FR-AUTH-003, FR-AUTH-005
 */
public record Account(
        UUID id,
        String email,
        String displayName,
        AppRole role,
        Instant deactivatedAt,
        Instant createdAt,
        Instant updatedAt) {

    public boolean isActive() {
        return deactivatedAt == null;
    }
}
