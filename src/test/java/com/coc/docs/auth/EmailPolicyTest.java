package com.coc.docs.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The email rules, and their agreement with the frontend copy (ADR-088).
 *
 * <p>The accepted and rejected lists are duplicated verbatim in
 * {@code frontend/lib/auth/emailPolicy.test.ts}. That duplication is
 * deliberate: the frontend copy is advisory (CONST-TECH-07) and this one
 * decides, so the failure worth catching is the two disagreeing — an address
 * the form accepts and the server refuses looks, to the person typing it, like
 * the form is broken.
 *
 * <p>// req: NFR-SEC-AUTH, ADR-088
 */
class EmailPolicyTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "person@example.com",
                "first.last@example.co.uk",
                "a+tag@example.com",
                "a_b-c@sub.domain.example.org",
                "x@e.io",
                "UPPER@EXAMPLE.COM"
            })
    @DisplayName("accepts a deliverable address")
    void accepts(String email) {
        assertThat(EmailPolicy.violations(email)).isEmpty();
        assertThat(EmailPolicy.isAcceptable(email)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "qqq", // no @ at all
                "qqq@qqq", // no TLD -- what @Email allowed
                "@example.com", // nothing before the @
                "person@", // nothing after the @
                "a@b@example.com", // two @
                "person @example.com", // a space
                "person@localhost", // no dot in the domain
                "person@192.168.1.1", // an IP literal
                "person@example..com", // an empty label
                "person@-example.com", // a label starting with a hyphen
                "person@example.c" // a one-letter TLD
            })
    @DisplayName("rejects an address that cannot receive mail")
    void rejects(String email) {
        assertThat(EmailPolicy.isAcceptable(email)).isFalse();
        assertThat(EmailPolicy.violations(email)).isNotEmpty();
    }

    /** The case this policy exists for: Jakarta's @Email accepts it. */
    @Test
    @DisplayName("rejects the address the built-in @Email would allow")
    void rejectsWhatAtEmailAllows() {
        assertThat(EmailPolicy.violations("qqq@qqq")).containsExactly("Add a domain ending, such as .com.");
    }

    @Test
    @DisplayName("names the specific problem rather than saying 'invalid'")
    void namesTheProblem() {
        assertThat(EmailPolicy.violations("qqq")).containsExactly("Include an @, as in name@example.com.");
        assertThat(EmailPolicy.violations("@example.com"))
                .containsExactly("Add the part before the @, as in name@example.com.");
        assertThat(EmailPolicy.violations("a@b@example.com"))
                .containsExactly("An email address can only contain one @.");
    }

    @Test
    @DisplayName("treats empty and blank as 'enter an email address'")
    void emptyIsOneMessage() {
        assertThat(EmailPolicy.violations("")).containsExactly("Enter an email address.");
        assertThat(EmailPolicy.violations("   ")).containsExactly("Enter an email address.");
        assertThat(EmailPolicy.violations(null)).containsExactly("Enter an email address.");
    }

    /**
     * The expensive failure is rejecting a real address: somebody cannot argue
     * with a form, and has no way to find out which character it objected to.
     * These are addresses this deployment actually holds.
     */
    @Test
    @DisplayName("accepts the shapes people actually use")
    void realAddresses() {
        assertThat(EmailPolicy.isAcceptable("oudompanha456@gmail.com")).isTrue();
        assertThat(EmailPolicy.isAcceptable("noreply-hrms@wecambodia.com")).isTrue();
        assertThat(EmailPolicy.isAcceptable("neo.chanthy8888@gmail.com")).isTrue();
    }

    @Test
    @DisplayName("enforces the RFC 5321 length limits")
    void lengths() {
        assertThat(EmailPolicy.isAcceptable("a".repeat(64) + "@example.com")).isTrue();
        assertThat(EmailPolicy.isAcceptable("a".repeat(65) + "@example.com")).isFalse();
    }

    @Test
    @DisplayName("complains about surrounding spaces rather than silently trimming them")
    void surroundingSpaces() {
        assertThat(EmailPolicy.violations(" person@example.com "))
                .contains("Remove the spaces around the address.");
    }

    @Nested
    @DisplayName("agreement with the frontend copy")
    class Parity {

        /** The same strings as frontend/lib/auth/emailPolicy.test.ts. */
        private static final List<String> ACCEPTED = List.of(
                "person@example.com",
                "first.last@example.co.uk",
                "a+tag@example.com",
                "a_b-c@sub.domain.example.org",
                "x@e.io",
                "UPPER@EXAMPLE.COM");

        private static final List<String> REJECTED = List.of(
                "qqq",
                "qqq@qqq",
                "@example.com",
                "person@",
                "a@b@example.com",
                "person @example.com",
                "person@localhost",
                "person@192.168.1.1",
                "person@example..com",
                "person@-example.com",
                "person@example.c");

        @Test
        @DisplayName("every address the frontend accepts, this accepts")
        void acceptedMatch() {
            assertThat(ACCEPTED).allMatch(EmailPolicy::isAcceptable);
        }

        @Test
        @DisplayName("every address the frontend rejects, this rejects")
        void rejectedMatch() {
            assertThat(REJECTED).noneMatch(EmailPolicy::isAcceptable);
        }
    }
}
