package com.coc.docs.web;

import com.coc.docs.auth.CurrentUser;
import com.coc.docs.integration.Integration;
import com.coc.docs.integration.IntegrationService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Connect / test / rotate / disconnect outgoing webhook integrations
 * (ADR-037).
 *
 * <p>Unlike {@code AdminClaudeUsageController}, this does not sit under the
 * generic {@code admin}-role rule for {@code /api/v1/admin/**} — every write
 * here is explicitly gated at {@code super_admin} in {@code SecurityConfig},
 * the "touches credentials, grants authority" floor
 * {@code /api/v1/admin/accounts/**} and {@code /api/v1/admin/roles/**}
 * already carry, because connecting/rotating/disconnecting stores or
 * discards a signing secret and testing makes a live outbound call to an
 * admin-supplied URL. The two {@code GET}s stay at the ordinary {@code admin}
 * floor.
 *
 * <p>// req: FR-INTEG-001, FR-INTEG-002, FR-INTEG-003, FR-INTEG-004, FR-INTEG-006, ADR-037
 */
@RestController
@RequestMapping("/api/v1/admin/integrations")
public class AdminIntegrationController {

    private final IntegrationService integrations;

    public AdminIntegrationController(IntegrationService integrations) {
        this.integrations = integrations;
    }

    @GetMapping
    List<ApiDtos.IntegrationResponse> list() {
        return integrations.list().stream().map(ApiDtos.IntegrationResponse::of).toList();
    }

    @GetMapping("/{id}/deliveries")
    List<ApiDtos.IntegrationDeliveryResponse> deliveries(@PathVariable UUID id) {
        return integrations.recentDeliveries(id).stream().map(ApiDtos.IntegrationDeliveryResponse::of).toList();
    }

    /** 201 with a {@code Location} header — a new connected integration at a new address. */
    @PostMapping
    ResponseEntity<ApiDtos.IntegrationSecretResponse> connect(
            @Valid @RequestBody ApiDtos.ConnectIntegrationRequest request, @AuthenticationPrincipal CurrentUser actor) {

        IntegrationService.Connected connected =
                integrations.connect(request.provider(), request.name(), request.targetUrl(), request.events(), actor);
        return ResponseEntity.created(URI.create("/api/v1/admin/integrations/" + connected.integration().id()))
                .body(ApiDtos.IntegrationSecretResponse.of(connected.integration(), connected.plaintextSecret()));
    }

    @PatchMapping("/{id}")
    ApiDtos.IntegrationResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody ApiDtos.UpdateIntegrationRequest request,
            @AuthenticationPrincipal CurrentUser actor) {

        Integration updated = integrations.rename(id, request.name(), request.events(), actor);
        return ApiDtos.IntegrationResponse.of(updated);
    }

    @PostMapping("/{id}/test")
    ApiDtos.TestIntegrationResponse test(@PathVariable UUID id, @AuthenticationPrincipal CurrentUser actor) {
        return ApiDtos.TestIntegrationResponse.of(integrations.test(id, actor).delivery());
    }

    @PostMapping("/{id}/rotate-secret")
    ApiDtos.IntegrationSecretResponse rotateSecret(@PathVariable UUID id, @AuthenticationPrincipal CurrentUser actor) {
        IntegrationService.Rotated rotated = integrations.rotateSecret(id, actor);
        return ApiDtos.IntegrationSecretResponse.of(rotated.integration(), rotated.plaintextSecret());
    }

    /** Re-activates a disconnected row in place, with a freshly issued secret — never a new row. */
    @PostMapping("/{id}/reconnect")
    ApiDtos.IntegrationSecretResponse reconnect(@PathVariable UUID id, @AuthenticationPrincipal CurrentUser actor) {
        IntegrationService.Reconnected reconnected = integrations.reconnect(id, actor);
        return ApiDtos.IntegrationSecretResponse.of(reconnected.integration(), reconnected.plaintextSecret());
    }

    /** Disconnects — never deletes the row. See {@code Integration}'s Javadoc. */
    @DeleteMapping("/{id}")
    ApiDtos.IntegrationResponse disconnect(@PathVariable UUID id, @AuthenticationPrincipal CurrentUser actor) {
        return ApiDtos.IntegrationResponse.of(integrations.disconnect(id, actor));
    }
}
