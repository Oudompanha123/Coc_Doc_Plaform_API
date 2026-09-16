package com.coc.docs.notify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where a tapped notification lands.
 *
 * <p>// req: ADR-025, ADR-027
 */
class NotificationTypeTest {

    private static final String ORIGIN = "https://docs.example.com";

    @Test
    @DisplayName("a path that already has a query gets the subject appended with &")
    void appendsToAnExistingQuery() {
        assertThat(NotificationType.NEW_REGISTRATION.deepLink(ORIGIN, "abc"))
                .isEqualTo("https://docs.example.com/users-roles?tab=pending&approve=abc");
    }

    @Test
    @DisplayName("a path with no query starts one with ?")
    void startsAQueryWhenThereIsNone() {
        // The bug this replaces: the separator was a hardcoded "&", which
        // happened to be right for the only type that existed. The second type
        // would have produced /settings&session=abc — a path, not a query, so
        // the client would have found no session id and opened a page with
        // nothing on it.
        assertThat(NotificationType.NEW_DEVICE_SIGN_IN.deepLink(ORIGIN, "abc"))
                .isEqualTo("https://docs.example.com/settings?session=abc");
    }

    @Test
    @DisplayName("no subject means no query string at all")
    void omitsTheParameterWhenThereIsNoSubject() {
        assertThat(NotificationType.NEW_DEVICE_SIGN_IN.deepLink(ORIGIN, null))
                .isEqualTo("https://docs.example.com/settings");
    }

    @Test
    @DisplayName("every type names the parameter its own page reads")
    void eachTypeCarriesItsOwnParameterName() {
        assertThat(NotificationType.NEW_REGISTRATION.deepLink(ORIGIN, "x")).contains("approve=x");
        assertThat(NotificationType.NEW_DEVICE_SIGN_IN.deepLink(ORIGIN, "x")).contains("session=x");
    }
}
