package com.coc.docs.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.coc.docs.PostgresTestBase;
import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link NotificationPreferenceRepository} against a real PostgreSQL — in
 * particular, that opt-out-by-absence (ADR-041) actually holds: a row exists
 * only for a type somebody has explicitly turned off, never for one left at
 * its default.
 *
 * <p>// req: ADR-041
 */
class NotificationPreferenceRepositoryIntegrationTest extends PostgresTestBase {

    @Autowired NotificationPreferenceRepository preferences;
    @Autowired AccountRepository accounts;
    @Autowired JdbcClient jdbc;

    private Account one;
    private Account two;

    @BeforeEach
    void setUp() {
        jdbc.sql("delete from notification_preference").update();
        one = accounts.insert("pref-one@example.com", "One", AppRole.USER, null, AccountStatus.ACTIVE);
        two = accounts.insert("pref-two@example.com", "Two", AppRole.USER, null, AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("a type nobody has touched is enabled by default")
    void defaultsToEnabled() {
        assertThat(preferences.isEnabled(one.id(), NotificationType.NEW_REGISTRATION)).isTrue();
        assertThat(preferences.findOverridesFor(one.id())).isEmpty();
    }

    @Test
    @DisplayName("turning a type off is a real, readable row")
    void turningOffPersists() {
        preferences.setEnabled(one.id(), NotificationType.NEW_DEVICE_SIGN_IN, false);

        assertThat(preferences.isEnabled(one.id(), NotificationType.NEW_DEVICE_SIGN_IN)).isFalse();
        assertThat(preferences.findOverridesFor(one.id()))
                .containsEntry(NotificationType.NEW_DEVICE_SIGN_IN.key(), false);
        // Never touched: this account's other type is untouched by the write.
        assertThat(preferences.isEnabled(one.id(), NotificationType.NEW_REGISTRATION)).isTrue();
    }

    @Test
    @DisplayName("turning a type back on removes the opt-out, not just flips a flag to true")
    void turningBackOnRestoresTheDefault() {
        preferences.setEnabled(one.id(), NotificationType.NEW_REGISTRATION, false);
        preferences.setEnabled(one.id(), NotificationType.NEW_REGISTRATION, true);

        assertThat(preferences.isEnabled(one.id(), NotificationType.NEW_REGISTRATION)).isTrue();
        assertThat(preferences.findOverridesFor(one.id()))
                .containsEntry(NotificationType.NEW_REGISTRATION.key(), true);
    }

    @Test
    @DisplayName("one account's preference never leaks onto another's")
    void scopedToAccount() {
        preferences.setEnabled(one.id(), NotificationType.NEW_REGISTRATION, false);

        assertThat(preferences.isEnabled(one.id(), NotificationType.NEW_REGISTRATION)).isFalse();
        assertThat(preferences.isEnabled(two.id(), NotificationType.NEW_REGISTRATION)).isTrue();
    }

    @Test
    @DisplayName("filterEnabled drops only the accounts that opted out, defaulting everyone else in")
    void filterEnabledDropsOnlyOptedOut() {
        preferences.setEnabled(two.id(), NotificationType.NEW_REGISTRATION, false);

        assertThat(preferences.filterEnabled(Set.of(one.id(), two.id()), NotificationType.NEW_REGISTRATION))
                .containsExactly(one.id());
    }

    @Test
    @DisplayName("filterEnabled with nobody opted out returns every candidate, unqueried result included")
    void filterEnabledWithNoOptOuts() {
        assertThat(preferences.filterEnabled(Set.of(one.id(), two.id()), NotificationType.NEW_DEVICE_SIGN_IN))
                .containsExactlyInAnyOrder(one.id(), two.id());
    }

    @Test
    @DisplayName("an empty candidate set is answered without a query, not with the invalid `in ()`")
    void filterEnabledWithEmptyInput() {
        assertThat(preferences.filterEnabled(Set.of(), NotificationType.NEW_REGISTRATION)).isEmpty();
    }
}
