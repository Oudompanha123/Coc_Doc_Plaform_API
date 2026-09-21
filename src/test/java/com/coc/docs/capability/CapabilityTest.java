package com.coc.docs.capability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Tests for the capability catalog — the "AX Role" choices (ADR-039) layered
 * on top of {@code AppRole} (ADR-038, filed as "ADR-037" in code/migrations).
 *
 * <p>{@code DEVELOPER}'s ordinal must not move: ADR-039 added {@code
 * TECH_LEAD} and {@code REVIEWER} after it specifically so nothing that
 * iterates {@link Capability#values()} is disturbed by the addition.
 *
 * <p>// req: ADR-016, ADR-037, ADR-039
 */
class CapabilityTest {

    @Test
    void developer_keeps_its_original_ordinal() {
        assertThat(Capability.DEVELOPER.ordinal()).isZero();
    }

    @Test
    void the_wire_form_matches_the_capability_table_keys() {
        // V24__capabilities.sql / V26__ax_role_capabilities.sql insert exactly
        // these keys.
        assertThat(Capability.DEVELOPER.key()).isEqualTo("developer");
        assertThat(Capability.TECH_LEAD.key()).isEqualTo("tech_lead");
        assertThat(Capability.REVIEWER.key()).isEqualTo("reviewer");
    }

    @Test
    void an_unrecognised_value_is_empty_rather_than_a_quiet_default() {
        assertThat(Capability.parse("root")).isEmpty();
        assertThat(Capability.parse(null)).isEmpty();
        assertThat(Capability.parse("DEVELOPER")).isEqualTo(Optional.of(Capability.DEVELOPER));
        assertThat(Capability.parse("tech_lead")).isEqualTo(Optional.of(Capability.TECH_LEAD));
        assertThat(Capability.parse("reviewer")).isEqualTo(Optional.of(Capability.REVIEWER));
    }

    @Test
    void authorities_use_the_capability_prefix_not_ROLE() {
        // Distinct from AppRole.authority()'s "ROLE_" prefix, so a
        // hasAnyAuthority(...) call site reads unambiguously as a capability
        // check, never a rank check.
        assertThat(Capability.DEVELOPER.authority()).isEqualTo("capability.developer");
        assertThat(Capability.TECH_LEAD.authority()).isEqualTo("capability.tech_lead");
        assertThat(Capability.REVIEWER.authority()).isEqualTo("capability.reviewer");
    }
}
