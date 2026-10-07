package com.coc.docs.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The {@code app.otp.*} switch for TOTP second-factor login (ADR-031).
 *
 * <p><b>Deliberately {@code @Value}-defaulted, not a required {@code
 * @ConfigurationProperties} block like {@link AppProperties}.</b>
 * {@code application.yml} is off-limits to the AI that wrote this class, per
 * this repo's {@code SEC-001}/{@code SEC-003} convention — the same wall
 * {@code RegistrationOtpMailer}'s Javadoc already documents hitting for
 * {@code spring.mail.host}. A {@code @Value} default means this feature is
 * fully functional with zero lines added to {@code application.yml}:
 * {@link #enabled()} defaults to {@code true} — real TOTP codes required —
 * which is the secure behaviour for a deployment that never configures this
 * at all.
 *
 * <p>To turn on the developer bypass locally, add to your own
 * {@code backend/.env.local} (loaded by {@link DotenvEnvironmentPostProcessor};
 * relaxed binding maps these onto {@code app.otp.*}, see
 * {@code backend/.env.example}):
 *
 * <pre>{@code
 * APP_OTP_ENABLED=false
 * APP_OTP_DEV_CODE=123456
 * }</pre>
 *
 * <p>{@link OtpDevModeGuard} refuses to start the application if this bypass
 * is active outside a {@code dev}/{@code test} Spring profile — see its
 * Javadoc.
 *
 * <p>// req: FR-AUTH-009, ADR-031
 */
@Component
public class OtpProperties {

    private final boolean enabled;
    private final String devCode;

    public OtpProperties(
            @Value("${app.otp.enabled:true}") boolean enabled,
            @Value("${app.otp.dev-code:123456}") String devCode) {
        this.enabled = enabled;
        this.devCode = devCode;
    }

    /** True (the default): a real TOTP code is required. False: {@link #devCode()} is accepted instead. */
    public boolean enabled() {
        return enabled;
    }

    /**
     * The fixed code accepted in place of a real TOTP when {@link #enabled()}
     * is false — never consulted otherwise.
     */
    public String devCode() {
        return devCode;
    }
}
