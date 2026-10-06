package com.coc.docs.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Which credential a per-account sender authenticates with (ADR-063, ADR-085).
 *
 * <p>No SMTP here. What is worth pinning is the choice <em>before</em> the
 * connection: whether XOAUTH2 is advertised, and what is put in the password
 * slot. Both are invisible until a relay rejects them, and the rejection reads
 * as a bad credential either way — so the distinction has to be asserted here
 * rather than discovered against Google.
 *
 * <p>// req: NFR-SEC-CHANNEL, ADR-085
 */
class PersonalMailSenderFactoryTest {

    private static final UUID ANA = UUID.randomUUID();

    private final GoogleMailOAuthService oauth = mock(GoogleMailOAuthService.class);
    private final PersonalMailSenderFactory factory = new PersonalMailSenderFactory("smtp.gmail.com", 587);

    private static AccountMailCredentialRepository.Credential appPassword() {
        return new AccountMailCredentialRepository.Credential(ANA, "ana@gmail.com", "app-password", null);
    }

    private static AccountMailCredentialRepository.Credential oauthGrant() {
        return new AccountMailCredentialRepository.Credential(ANA, "ana@gmail.com", null, "refresh-token");
    }

    @Nested
    @DisplayName("an App Password account")
    class AppPasswordAccount {

        @Test
        @DisplayName("authenticates with the password and does not advertise XOAUTH2")
        void usesPasswordAuth() {
            JavaMailSenderImpl sender =
                    (JavaMailSenderImpl) factory.senderOrEmpty(appPassword(), oauth).orElseThrow();

            assertThat(sender.getUsername()).isEqualTo("ana@gmail.com");
            assertThat(sender.getPassword()).isEqualTo("app-password");
            assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.auth.mechanisms"))
                    .isNull();
        }

        /** The OAuth service must not be consulted at all for this kind. */
        @Test
        @DisplayName("never asks for an access token")
        void doesNotMintAToken() {
            factory.senderOrEmpty(appPassword(), oauth);

            org.mockito.Mockito.verifyNoInteractions(oauth);
        }
    }

    @Nested
    @DisplayName("an OAuth account")
    class OauthAccount {

        /**
         * The access token goes in the password slot, which is how Jakarta
         * Mail carries a bearer token — the mechanism, not the field, is what
         * makes it XOAUTH2. Naming the mechanism is the part that matters:
         * Gmail also offers PLAIN, and a client that picks PLAIN sends the
         * token as a password and is refused.
         */
        @Test
        @DisplayName("authenticates with a freshly minted access token over XOAUTH2")
        void usesXoauth2() {
            when(oauth.accessTokenFor(ANA, "refresh-token")).thenReturn(Optional.of("ya29.access"));

            JavaMailSenderImpl sender =
                    (JavaMailSenderImpl) factory.senderOrEmpty(oauthGrant(), oauth).orElseThrow();

            assertThat(sender.getUsername()).isEqualTo("ana@gmail.com");
            assertThat(sender.getPassword()).isEqualTo("ya29.access");
            assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.auth.mechanisms"))
                    .isEqualTo("XOAUTH2");
        }

        /**
         * A revoked or expired grant. Empty rather than a sender that cannot
         * work, so the caller falls back to the shared sender and the person
         * still gets their attendance mail (ADR-085 §4.2).
         */
        @Test
        @DisplayName("yields no sender when Google will not mint a token")
        void revokedGrantYieldsNothing() {
            when(oauth.accessTokenFor(ANA, "refresh-token")).thenReturn(Optional.empty());

            assertThat(factory.senderOrEmpty(oauthGrant(), oauth)).isEmpty();
        }

        /** The stored secret is the refresh token; it must never reach the relay. */
        @Test
        @DisplayName("never sends the refresh token itself")
        void refreshTokenNeverLeaves() {
            when(oauth.accessTokenFor(ANA, "refresh-token")).thenReturn(Optional.of("ya29.access"));

            JavaMailSenderImpl sender =
                    (JavaMailSenderImpl) factory.senderOrEmpty(oauthGrant(), oauth).orElseThrow();

            assertThat(sender.getPassword()).isNotEqualTo("refresh-token");
        }
    }
}
