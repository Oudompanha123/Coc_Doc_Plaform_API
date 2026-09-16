package com.coc.docs.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That authentication is re-established on the container's second dispatch.
 *
 * <p><b>This asserts a setting, not a behaviour, and that is deliberate.</b> The
 * behaviour needs a real servlet container to observe —
 * {@code NotificationStreamAuthIntegrationTest} does that, and skips without
 * Docker. This one always runs, and it exists because the setting is a single
 * overridden method that reads like boilerplate: the next person to tidy this
 * class is one deletion away from breaking every streaming response in the
 * application, and the javadoc alone has not historically been enough.
 *
 * <p>What it guards: an {@code SseEmitter} response is dispatched twice, and
 * {@code OncePerRequestFilter} skips the second pass by default. Spring
 * Security authorizes that pass regardless, so skipping it means a caller who
 * was authenticated on the way in is anonymous on the way back and gets
 * {@code Access Denied} — as the notification stream did on 2026-09-16.
 *
 * <p>// req: FR-AUTH-005, ADR-011, ADR-028
 */
class SessionAuthenticationFilterAsyncTest {

    @Test
    @DisplayName("the filter runs on async dispatches, not only the first one")
    void runsOnAsyncDispatch() {
        SessionAuthenticationFilter filter = new SessionAuthenticationFilter(mock(SessionService.class));

        assertThat(filter.shouldNotFilterAsyncDispatch())
                .as("an SseEmitter response is authorized on its async dispatch too, "
                        + "and is denied there unless this filter re-reads the cookie")
                .isFalse();
    }
}
