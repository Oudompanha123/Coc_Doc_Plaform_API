package com.coc.docs.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.coc.docs.auth.KeycloakIdTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Whether Keycloak wiring switches on, and whether the application still boots
 * when it does not.
 *
 * <p>The case that matters is the middle one. {@code @ConditionalOnProperty}
 * matches on a key being <em>present</em>, so a placeholder like
 * {@code issuer-uri: ${KEYCLOAK_ISSUER_URI:}} — the obvious thing to write
 * when adding the key before the realm exists — would have activated this
 * configuration with no host and failed the boot. A deployment that has not
 * stood Keycloak up has to be indistinguishable from one that never mentioned
 * it.
 *
 * <p>// req: FR-AUTH-001, ADR-013
 */
class KeycloakAuthConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(
                    org.springframework.boot.autoconfigure.AutoConfigurations.of(
                            ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(KeycloakAuthConfig.class)
            .withBean(KeycloakProperties.class, () -> new KeycloakProperties(null, null));

    @Nested
    @DisplayName("when no realm is configured")
    class Dormant {

        @Test
        void the_property_being_absent_creates_nothing() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(JwtDecoder.class);
                assertThat(context).doesNotHaveBean(KeycloakIdTokenService.class);
            });
        }

        @Test
        void a_blank_issuer_creates_nothing_either() {
            runner.withPropertyValues("app.keycloak.issuer-uri=").run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(JwtDecoder.class);
                assertThat(context).doesNotHaveBean(KeycloakIdTokenService.class);
            });
        }

        @Test
        void whitespace_is_not_a_realm() {
            runner.withPropertyValues("app.keycloak.issuer-uri=   ").run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(JwtDecoder.class);
            });
        }
    }

    @Nested
    @DisplayName("when a realm is configured")
    class Active {

        private final ApplicationContextRunner configured = new ApplicationContextRunner()
                .withUserConfiguration(KeycloakAuthConfig.class)
                .withBean(
                        KeycloakProperties.class,
                        () -> new KeycloakProperties("https://kc.example.com/realms/coc", "coc-docs"))
                .withPropertyValues("app.keycloak.issuer-uri=https://kc.example.com/realms/coc");

        @Test
        void both_beans_are_created() {
            configured.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(JwtDecoder.class);
                assertThat(context).hasSingleBean(KeycloakIdTokenService.class);
            });
        }

        @Test
        void building_the_decoder_does_not_contact_keycloak() {
            // The host does not resolve. If startup fetched the JWK set — as
            // spring.security.oauth2.resourceserver.jwt.issuer-uri would — this
            // context would fail instead of coming up.
            new ApplicationContextRunner()
                    .withUserConfiguration(KeycloakAuthConfig.class)
                    .withBean(
                            KeycloakProperties.class,
                            () -> new KeycloakProperties("https://no-such-host.invalid/realms/coc", "coc-docs"))
                    .withPropertyValues("app.keycloak.issuer-uri=https://no-such-host.invalid/realms/coc")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        void a_realm_without_a_client_id_refuses_to_start() {
            // Not a permissive default: with no audience the verifier would
            // accept a token minted for any other client in the same realm.
            new ApplicationContextRunner()
                    .withUserConfiguration(KeycloakAuthConfig.class)
                    .withBean(
                            KeycloakProperties.class,
                            () -> new KeycloakProperties("https://kc.example.com/realms/coc", null))
                    .withPropertyValues("app.keycloak.issuer-uri=https://kc.example.com/realms/coc")
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Nested
    @DisplayName("the JWK set URI")
    class JwkSetUri {

        @Test
        void is_derived_from_the_realm() {
            assertThat(new KeycloakProperties("https://kc.example.com/realms/coc", "x").jwkSetUri())
                    .isEqualTo("https://kc.example.com/realms/coc/protocol/openid-connect/certs");
        }

        @Test
        void tolerates_a_trailing_slash() {
            assertThat(new KeycloakProperties("https://kc.example.com/realms/coc/", "x").jwkSetUri())
                    .isEqualTo("https://kc.example.com/realms/coc/protocol/openid-connect/certs");
        }
    }
}
