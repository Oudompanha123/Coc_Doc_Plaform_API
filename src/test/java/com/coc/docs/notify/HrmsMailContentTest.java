package com.coc.docs.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The rule that decides whose attendance somebody is shown.
 *
 * <p>Worth its own class rather than a nest inside the service's tests: a
 * wrong answer here discloses one employee's clock-in times to another
 * (NFR-SEC-PII), and the interesting cases are all about the matcher, not
 * about mailboxes or scoping.
 *
 * <p>// req: FR-OPS-001, NFR-SEC-PII, ADR-042
 */
class HrmsMailContentTest {

    private static HrmsMailContent.Entry entry() {
        return new HrmsMailContent.Entry("17:00", "17:38", "Normal");
    }

    private static HrmsMailContent report(String... names) {
        List<HrmsMailContent.Row> rows =
                java.util.Arrays.stream(names)
                        .map(n -> new HrmsMailContent.Row(n, entry(), entry()))
                        .toList();
        return new HrmsMailContent(
                "Attendance on 28-Aug-2026", LocalDate.of(2026, 8, 28), "COOCON Department",
                rows, List.of(), "do not reply");
    }

    @Nested
    class MatchingByEmail {

        @Test
        @DisplayName("the address's letters, in any order, spell the name")
        void tokensInAnyOrder() {
            var scoped = report("Veth Sam Oeun", "Ken Vantha")
                    .onlyRowsFor(null, "samoeunveth99@gmail.com");

            assertThat(scoped.rows()).hasSize(1);
            assertThat(scoped.rows().get(0).name()).isEqualTo("Veth Sam Oeun");
        }

        @Test
        @DisplayName("the same order works too, and so does punctuation in the address")
        void sameOrderAndPunctuation() {
            assertThat(report("Ken Vantha").onlyRowsFor(null, "ken.vantha@gmail.com").rows()).hasSize(1);
            assertThat(report("Nin Srey Nuth").onlyRowsFor(null, "ninsreynuth19@gmail.com").rows()).hasSize(1);
        }

        @Test
        @DisplayName("a partial name is NOT a match -- one token of two is a coincidence, not a person")
        void partialNamesDoNotMatch() {
            // The real case this exists for: oudompanha456@ against
            // "Chamreun Oudompanha" would match on family name alone.
            assertThat(report("Chamreun Oudompanha").onlyRowsFor(null, "oudompanha456@gmail.com").rows())
                    .isEmpty();
        }

        @Test
        @DisplayName("an unrelated address matches nobody, rather than the nearest row")
        void unrelatedAddressMatchesNobody() {
            assertThat(report("Veth Sam Oeun", "Ken Vantha", "Sen Navy")
                            .onlyRowsFor(null, "sarayuthcoding@gmail.com")
                            .rows())
                    .isEmpty();
        }

        @Test
        @DisplayName("letters that merely rearrange into the name are not enough -- tokens must be whole")
        void anagramsAreNotMatches() {
            // "vethsamoen" is one letter short; "vethsamoeunx" one over.
            assertThat(report("Veth Sam Oeun").onlyRowsFor(null, "vethsamoen@gmail.com").rows()).isEmpty();
            assertThat(report("Veth Sam Oeun").onlyRowsFor(null, "vethsamoeunx@gmail.com").rows()).isEmpty();
        }

        @Test
        @DisplayName("details and note survive the narrowing -- a leave mail concerns the person reading it")
        void detailsAndNoteSurvive() {
            var scoped = report("Ken Vantha").onlyRowsFor(null, "nobody@example.com");

            assertThat(scoped.rows()).isEmpty();
            assertThat(scoped.note()).isEqualTo("do not reply");
            assertThat(scoped.department()).isEqualTo("COOCON Department");
        }
    }

    @Nested
    class ExplicitNameWins {

        @Test
        @DisplayName("account.hrms_name overrides the address, for an address the names cannot be derived from")
        void explicitOverridesEmail() {
            var scoped = report("Veth Sam Oeun", "Ken Vantha")
                    .onlyRowsFor("Ken Vantha", "samoeunveth99@gmail.com");

            assertThat(scoped.rows()).hasSize(1);
            assertThat(scoped.rows().get(0).name()).isEqualTo("Ken Vantha");
        }

        @Test
        @DisplayName("an explicit name that matches nobody yields nothing, never a fallback to the address")
        void explicitNameThatMatchesNobody() {
            assertThat(report("Veth Sam Oeun").onlyRowsFor("Someone Else", "samoeunveth99@gmail.com").rows())
                    .isEmpty();
        }
    }
}
