package com.coc.docs.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The password rules, and their agreement with the frontend copy (ADR-087).
 *
 * <p>The accepted and rejected lists here are duplicated verbatim in
 * {@code frontend/lib/auth/passwordPolicy.test.ts}. That duplication is
 * deliberate: the frontend copy is advisory (CONST-TECH-07) and this one
 * decides, so the failure worth catching is the two disagreeing — a password
 * the form accepts and the server refuses looks, to the person typing it, like
 * the form is broken.
 *
 * <p>// req: NFR-SEC-AUTH, ADR-087
 */
class PasswordPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"hunter22", "Str0ng!Passphrase", "        a", "ភាសាខ្មែរabc"})
    @DisplayName("accepts a password of at least the minimum length")
    void accepts(String password) {
        assertThat(PasswordPolicy.violations(password)).isEmpty();
        assertThat(PasswordPolicy.isAcceptable(password)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"qqq", "qqq111", "1234567"})
    @DisplayName("rejects a password under the minimum length")
    void rejects(String password) {
        assertThat(PasswordPolicy.isAcceptable(password)).isFalse();
        assertThat(PasswordPolicy.violations(password)).isNotEmpty();
    }

    @Test
    @DisplayName("rejects an empty password with one message")
    void emptyIsOneMessage() {
        assertThat(PasswordPolicy.violations("")).containsExactly("Enter a password.");
        assertThat(PasswordPolicy.violations(null)).containsExactly("Enter a password.");
    }

    @Test
    @DisplayName("the floor is eight, exactly")
    void floorIsEight() {
        assertThat(PasswordPolicy.isAcceptable("a".repeat(7))).isFalse();
        assertThat(PasswordPolicy.isAcceptable("a".repeat(8))).isTrue();
    }

    /**
     * Deliberately no composition rules (ADR-087 v1.1, NIST SP 800-63B
     * §5.1.1.2). These pin that, so reintroducing one is a visible decision
     * rather than a quiet tightening that starts refusing passwords people
     * already hold.
     */
    @Test
    @DisplayName("requires no particular character class")
    void noCompositionRules() {
        assertThat(PasswordPolicy.isAcceptable("alllowercase")).isTrue();
        assertThat(PasswordPolicy.isAcceptable("ALLUPPERCASE")).isTrue();
        assertThat(PasswordPolicy.isAcceptable("123456789")).isTrue();
        assertThat(PasswordPolicy.isAcceptable("!!!!!!!!!")).isTrue();
    }

    @Test
    @DisplayName("names the length rule, in the words the form shows")
    void namesTheRule() {
        assertThat(PasswordPolicy.violations("qqq")).containsExactly("Use at least 8 characters.");
    }

    @Test
    @DisplayName("rejects a password past the bcrypt-shaped ceiling")
    void ceiling() {
        assertThat(PasswordPolicy.isAcceptable("a".repeat(201))).isFalse();
        assertThat(PasswordPolicy.isAcceptable("a".repeat(200))).isTrue();
    }

    @Nested
    @DisplayName("agreement with the frontend copy")
    class Parity {

        /** The same strings as frontend/lib/auth/passwordPolicy.test.ts. */
        private static final List<String> ACCEPTED =
                List.of("hunter22", "Str0ng!Passphrase", "        a", "ភាសាខ្មែរabc");

        private static final List<String> REJECTED = List.of("qqq", "qqq111", "1234567");

        @Test
        @DisplayName("every case the frontend accepts, this accepts")
        void acceptedMatch() {
            assertThat(ACCEPTED).allMatch(PasswordPolicy::isAcceptable);
        }

        @Test
        @DisplayName("every case the frontend rejects, this rejects")
        void rejectedMatch() {
            assertThat(REJECTED).noneMatch(PasswordPolicy::isAcceptable);
        }
    }
}
