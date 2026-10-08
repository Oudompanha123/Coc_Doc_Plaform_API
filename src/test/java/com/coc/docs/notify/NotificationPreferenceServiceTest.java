package com.coc.docs.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.coc.docs.account.AppRole;
import com.coc.docs.auth.TestCallers;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which preference switches each role is offered (ADR-041): only the types
 * that role can ever receive.
 *
 * <p>// req: ADR-041
 */
class NotificationPreferenceServiceTest {

    private final NotificationPreferenceRepository repository = mock(NotificationPreferenceRepository.class);
    private final NotificationPreferenceService service = new NotificationPreferenceService(repository);

    private List<String> keysListedFor(AppRole role) {
        return service.listFor(TestCallers.user(role)).stream()
                .map(p -> p.type().key())
                .toList();
    }

    @Test
    @DisplayName("a normal user is offered only new-device sign-in and announcements")
    void userSeesTwoTypes() {
        when(repository.findOverridesFor(any())).thenReturn(Map.of());

        assertThat(keysListedFor(AppRole.USER)).containsExactly("auth.new-device", "announcement.published");
    }

    @Test
    @DisplayName("a user's stored opt-out is still reported on the types they do see")
    void userKeepsTheirOptOut() {
        when(repository.findOverridesFor(any())).thenReturn(Map.of("announcement.published", false));

        assertThat(service.listFor(TestCallers.user(AppRole.USER)))
                .extracting(p -> p.type().key() + "=" + p.enabled())
                .containsExactly("auth.new-device=true", "announcement.published=false");
    }

    @Test
    @DisplayName("a super_admin is offered every type")
    void superAdminSeesEverything() {
        when(repository.findOverridesFor(any())).thenReturn(Map.of());

        assertThat(keysListedFor(AppRole.SUPER_ADMIN)).hasSize(NotificationType.values().length);
    }
}
