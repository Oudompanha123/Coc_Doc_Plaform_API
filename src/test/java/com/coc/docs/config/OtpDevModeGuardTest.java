package com.coc.docs.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * No Spring context — pure unit tests of the fail-fast check, mirroring
 * {@code KeycloakIdTokenServiceTest}'s constructor-guard tests exactly.
 *
 * <p>// req: NFR-SEC-AUTH, ADR-028
 */
class OtpDevModeGuardTest {

    @Test
    void refusesTheFakeCodeBypassOutsideDevOrTest() {
        assertThatThrownBy(() -> new OtpDevModeGuard(new OtpProperties(false, "123456"), new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must never reach production");
    }

    @Test
    void allowsTheFakeCodeBypassUnderTheDevProfile() {
        MockEnvironment devProfile = new MockEnvironment();
        devProfile.addActiveProfile("dev");

        assertThatCode(() -> new OtpDevModeGuard(new OtpProperties(false, "123456"), devProfile))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsTheFakeCodeBypassUnderTheTestProfile() {
        MockEnvironment testProfile = new MockEnvironment();
        testProfile.addActiveProfile("test");

        assertThatCode(() -> new OtpDevModeGuard(new OtpProperties(false, "123456"), testProfile))
                .doesNotThrowAnyException();
    }

    @Test
    void neverGuardsAgainstTheSecureDefault() {
        // enabled=true (the default): the bypass isn't active at all, so no
        // profile is required.
        assertThatCode(() -> new OtpDevModeGuard(new OtpProperties(true, "123456"), new MockEnvironment()))
                .doesNotThrowAnyException();
    }
}
