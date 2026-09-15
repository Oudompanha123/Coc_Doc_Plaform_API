package com.coc.docs.account;

import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

/**
 * Where a self-uploaded profile photo actually lives (ADR-026).
 *
 * <p>An {@link org.springframework.beans.factory.ObjectProvider}-injected
 * collaborator in {@code SessionService}, the same optional-bean shape as
 * {@code KeycloakIdTokenService}: a deployment that has not configured object
 * storage (CONST-TECH-08 — the provider is deferred, ADR-012 §2) must still
 * boot and serve every other route. {@code SessionService} reports a clear
 * refusal when no implementation is present, rather than the application
 * failing to start over a feature nobody has to use yet.
 */
public interface AvatarStorage {

    /**
     * Validates {@code file} and stores it under a key namespaced to {@code
     * accountId}, returning the URL the account row should hold.
     *
     * @throws com.coc.docs.web.ApiException.class badRequest for anything
     *     that fails validation — wrong content type, over the size limit, or
     *     bytes that do not actually decode as one of the allowed image
     *     formats. A caller's mistake, not a storage failure.
     */
    String upload(UUID accountId, MultipartFile file);

    /**
     * Best-effort removal of a previously stored object. A URL this storage
     * did not create, or one already gone, is not an error — the account row
     * is the source of truth, and a dangling object left behind costs storage,
     * not correctness.
     */
    void delete(String avatarUrl);
}
