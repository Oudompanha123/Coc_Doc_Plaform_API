package com.coc.docs.web;

import com.coc.docs.auth.CurrentUser;
import com.coc.docs.notify.DevicePlatform;
import com.coc.docs.notify.DeviceTokenRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where a signed-in person registers a device to be notified on (ADR-025).
 *
 * <p>Open to any authenticated caller, not just {@code super_admin}. Only
 * super admins receive anything today, but registering a destination is not
 * the same act as being sent to — restricting it by role would mean a second
 * place to change the moment a notification exists for anyone else, and would
 * make the endpoint itself leak who the approvers are.
 *
 * <p>A caller can only ever address their own account: the id comes from the
 * session, never from the request body. Without that, one signed-in person
 * could subscribe their device to another person's notifications.
 *
 * <p>// req: FR-AUTH-003, ADR-025
 */
@RestController
@RequestMapping("/api/v1/notifications/devices")
@Tag(name = "Notifications", description = "Push notification destinations")
public class DeviceTokenController {

    private final DeviceTokenRepository devices;

    public DeviceTokenController(DeviceTokenRepository devices) {
        this.devices = devices;
    }

    /**
     * The FCM registration token for this browser or app instance.
     *
     * @param token the token itself. Long and opaque; the cap is a sanity
     *     bound, not a format claim.
     * @param platform {@code web}, {@code android} or {@code ios}. Absent means
     *     web, which is the only client that exists today.
     */
    public record RegisterDeviceRequest(
            @NotBlank @Size(max = 4096) String token, String platform) {}

    /**
     * Records this device, or refreshes one already known.
     *
     * <p>Idempotent: the client re-registers on the SDK's own schedule, and a
     * repeat must not be an error. 204, because the caller already holds
     * everything it sent.
     */
    @PostMapping
    @Operation(summary = "Register this device for push notifications")
    ResponseEntity<Void> register(
            @Valid @RequestBody RegisterDeviceRequest request,
            @AuthenticationPrincipal CurrentUser actor,
            @org.springframework.web.bind.annotation.RequestHeader(
                            value = "User-Agent", required = false)
                    String userAgent) {

        // Absent means web, the only client that exists today. A value that is
        // present but unrecognised is a caller's mistake and is refused rather
        // than quietly treated as web.
        DevicePlatform platform = (request.platform() == null || request.platform().isBlank())
                ? DevicePlatform.WEB
                : DevicePlatform.parse(request.platform())
                        .orElseThrow(() -> ApiException.badRequest("unknown platform \""
                                + request.platform() + "\"; expected web, android or ios"));

        devices.upsert(request.token(), actor.id(), platform, userAgent);
        return ResponseEntity.noContent().build();
    }

    /**
     * Forgets this device — the person signed out, or turned notifications off.
     *
     * <p>Deletes by token alone rather than by token and account. The token
     * identifies one app instance and the person holding it is the one asking;
     * requiring a match would strand rows whenever a shared browser changed
     * hands, which is the case the upsert already handles by reassigning.
     */
    @DeleteMapping("/{token}")
    @Operation(summary = "Stop sending push notifications to this device")
    ResponseEntity<Void> unregister(@PathVariable String token) {
        devices.delete(token);
        return ResponseEntity.noContent().build();
    }
}
