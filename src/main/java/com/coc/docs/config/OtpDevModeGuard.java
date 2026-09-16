package com.coc.docs.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the application if the TOTP developer bypass
 * ({@code app.otp.enabled=false}, {@link OtpProperties#devCode()}) is active
 * outside a {@code dev} or {@code test} Spring profile (ADR-031).
 *
 * <p>Mirrors {@code KeycloakIdTokenService}'s existing constructor-time
 * check almost exactly, and for the same reason: a security control that is
 * silently permissive is worse than one that is loud and broken. Fail the
 * deploy, not a login attempt — by the time someone is signing in, it is too
 * late to notice that "test mode" was left on in production.
 *
 * <p><b>Deny by default.</b> An environment nobody bothered to name a
 * profile for is treated as production, not as safe — the same posture
 * {@code SecurityConfig}'s {@code anyRequest()} default-deny rule already
 * takes for an unmatched route.
 *
 * <p>// req: NFR-SEC-AUTH, ADR-031
 */
@Component
public class OtpDevModeGuard {

    public OtpDevModeGuard(OtpProperties otp, Environment environment) {
        boolean isDevOrTestProfile = environment.matchesProfiles("dev", "test");
        if (!otp.enabled() && !isDevOrTestProfile) {
            throw new IllegalStateException(
                    "app.otp.enabled=false (the fake/test TOTP bypass) is active, but neither the 'dev' "
                            + "nor the 'test' Spring profile is. Refusing to start: this bypass must never reach "
                            + "production. Set app.otp.enabled=true (or remove the override entirely -- true is "
                            + "the default), or activate the 'dev'/'test' profile if this really is a "
                            + "non-production environment.");
        }
    }
}
