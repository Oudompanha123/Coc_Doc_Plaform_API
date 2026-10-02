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
 * the form is broken. One list on each side makes a one-sided change fail
 * somewhere rather than in production.
 *
 * <p>// req: NFR-SEC-AUTH, ADR-087
 */
class PasswordPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"Str0ng!Passphrase", "aA1!aA1!aA1!", "Khmer-ភាសា1A!x", "p@ssW0rd-with-length"})
    @DisplayName("accepts a password meeting every rule")
    void accepts(String password) {
        assertThat(PasswordPolicy.violations(password)).isEmpty();
        assertThat(PasswordPolicy.isAcceptable(password)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "qqq", // far too short -- the one from the bug report
                "Passw0rd!", // nine characters, still under the floor
                "alllowercase1!", // no uppercase
                "ALLUPPERCASE1!", // no lowercase
                "NoDigitsHere!!", // no number
                "NoSymbolsHere1", // no symbol
                "            " // whitespace only
            })
    @DisplayName("rejects a password breaking any rule")
    void rejects(String password) {
        assertThat(PasswordPolicy.isAcceptable(password)).isFalse();
        assertThat(PasswordPolicy.violations(password)).isNotEmpty();
    }

    @Test
    @DisplayName("rejects an empty password with one message, not five")
    void emptyIsOneMessage() {
        assertThat(PasswordPolicy.violations("")).containsExactly("Enter a password.");
        assertThat(PasswordPolicy.violations(null)).containsExactly("Enter a password.");
    }

    @Test
    @DisplayName("the floor is 12, not the 8 it replaces")
    void twelveNotEight() {
        assertThat(PasswordPolicy.isAcceptable("aA1!aA1!")).isFalse(); // 8
        assertThat(PasswordPolicy.isAcceptable("aA1!aA1!aA1!")).isTrue(); // 12
    }

    /**
     * Reporting one problem at a time is what trains people to append "1!"
     * until the complaining stops. The exact list and order are pinned because
     * the frontend renders the same strings.
     */
    @Test
    @DisplayName("reports every unmet rule at once, in a fixable order")
    void reportsEverythingAtOnce() {
        assertThat(PasswordPolicy.violations("qqq"))
                .containsExactly(
                        "Use at least 12 characters.",
                        "Include an uppercase letter.",
                        "Include a number.",
                        "Include a symbol, such as ! ? @ or #.");
    }

    @Nested
    @DisplayName("what counts as a symbol")
    class Symbols {

        /** By exclusion, so a symbol from any script counts. */
        @Test
        @DisplayName("punctuation and currency from any script")
        void anyScript() {
            assertThat(PasswordPolicy.isAcceptable("Aa1aaaaaaaaa€")).isTrue();
            assertThat(PasswordPolicy.isAcceptable("Aa1aaaaaaaaa。")).isTrue();
        }

        @Test
        @DisplayName("a space is not a symbol")
        void spaceIsNot() {
            assertThat(PasswordPolicy.violations("Aa1 aaaaaaaaa"))
                    .contains("Include a symbol, such as ! ? @ or #.");
        }
    }

    @Nested
    @DisplayName("agreement with the frontend copy")
    class Parity {

        /**
         * The same strings as frontend/lib/auth/passwordPolicy.test.ts. If
         * this list and that one drift, one of the two policies has changed
         * alone, and the symptom in production is a form that refuses what the
         * server accepts or accepts what it refuses.
         */
        private static final List<String> ACCEPTED =
                List.of("Str0ng!Passphrase", "aA1!aA1!aA1!", "Khmer-ភាសា1A!x", "p@ssW0rd-with-length");

        private static final List<String> REJECTED = List.of(
                "qqq",
                "Passw0rd!",
                "alllowercase1!",
                "ALLUPPERCASE1!",
                "NoDigitsHere!!",
                "NoSymbolsHere1",
                "            ");

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
