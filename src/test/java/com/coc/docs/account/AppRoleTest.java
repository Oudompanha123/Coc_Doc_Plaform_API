package com.coc.docs.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Tests for the role ladder.
 *
 * <p>{@code isAtLeast} relies on declaration order, and every authorisation
 * decision in the system goes through it. A reordering of the enum would
 * silently invert those decisions and compile without complaint, which is why
 * the order is asserted here rather than left as a comment.
 *
 * <p>// req: FR-AUTH-005, FR-AUTH-006, FR-AUTH-008
 */
class AppRoleTest {

    @Test
    void the_ladder_is_in_ascending_privilege_order() {
        assertThat(AppRole.values())
                .containsExactly(
                        AppRole.GUEST, AppRole.USER, AppRole.ADMIN, AppRole.SUPER_ADMIN);
    }

    @Test
    void a_role_satisfies_itself_and_everything_below_it() {
        assertThat(AppRole.ADMIN.isAtLeast(AppRole.GUEST)).isTrue();
        assertThat(AppRole.ADMIN.isAtLeast(AppRole.USER)).isTrue();
        assertThat(AppRole.ADMIN.isAtLeast(AppRole.ADMIN)).isTrue();
    }

    @Test
    void a_role_does_not_satisfy_anything_above_it() {
        // FR-AUTH-008 in one assertion: an admin publishes content, and granting
        // roles is a different kind of authority.
        assertThat(AppRole.ADMIN.isAtLeast(AppRole.SUPER_ADMIN)).isFalse();
        assertThat(AppRole.USER.isAtLeast(AppRole.ADMIN)).isFalse();
        assertThat(AppRole.GUEST.isAtLeast(AppRole.USER)).isFalse();
    }

    @Test
    void the_wire_form_matches_the_postgres_enum_labels() {
        // V1__initial_schema.sql declares: guest, user, admin, super_admin.
        assertThat(AppRole.GUEST.wire()).isEqualTo("guest");
        assertThat(AppRole.USER.wire()).isEqualTo("user");
        assertThat(AppRole.ADMIN.wire()).isEqualTo("admin");
        assertThat(AppRole.SUPER_ADMIN.wire()).isEqualTo("super_admin");
    }

    @Test
    void an_unrecognised_value_is_empty_rather_than_a_quiet_downgrade() {
        // Defaulting to GUEST would be the safe direction and would also hide a
        // drift between the enum and the schema at the point it is read.
        assertThat(AppRole.parse("root")).isEmpty();
        assertThat(AppRole.parse(null)).isEmpty();
        assertThat(AppRole.parse("SUPER_ADMIN")).isEqualTo(Optional.of(AppRole.SUPER_ADMIN));
    }

    @Test
    void authorities_use_the_ROLE_prefix_spring_security_expects() {
        assertThat(AppRole.SUPER_ADMIN.authority()).isEqualTo("ROLE_SUPER_ADMIN");
    }
}
