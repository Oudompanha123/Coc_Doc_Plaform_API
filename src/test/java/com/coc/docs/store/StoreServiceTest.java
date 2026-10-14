package com.coc.docs.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.coc.docs.account.AppRole;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Who may open an app's page ({@code /store-<number>}): a user sees exactly
 * what the list would show them, an admin sees every app (ADR-108).
 *
 * <p>// req: ADR-108
 */
class StoreServiceTest {

    private StoreRepository repository;
    private StoreService service;

    private final CurrentUser user = caller(AppRole.USER);
    private final CurrentUser admin = caller(AppRole.ADMIN);

    private static CurrentUser caller(AppRole role) {
        CurrentUser caller = mock(CurrentUser.class);
        when(caller.role()).thenReturn(role);
        return caller;
    }

    @BeforeEach
    void setUp() {
        repository = mock(StoreRepository.class);
        service = new StoreService(
                repository, mock(StoreFileStorage.class), mock(AuditRepository.class), new ObjectMapper());
    }

    private StoreApp app(long number, boolean isPublic) {
        StoreApp app = new StoreApp(UUID.randomUUID(), number, "COC-Workbench-Setup-1.2.1.exe", "", StorePlatform.WINDOWS,
                isPublic, null, null, Instant.now(), Instant.now());
        when(repository.findAppByNumber(number)).thenReturn(Optional.of(app));
        return app;
    }

    private void withBuilds(StoreApp app, int count) {
        List<StoreRelease> releases = java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new StoreRelease(UUID.randomUUID(), app.id(), "1.2." + i, StoreEnvironment.PRODUCTION, "",
                        "a.exe", "windows/1.2." + i + "/production/" + app.id() + "/a.exe", 1, "0".repeat(64), Instant.now()))
                .toList();
        when(repository.findReleases(List.of(app.id()))).thenReturn(releases);
    }

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aUserOpensAPublicAppWithBuilds() {
        StoreApp app = app(1, true);
        withBuilds(app, 2);

        StoreService.CatalogEntry entry = service.entryForReader(1, user);

        assertThat(entry.app().name()).isEqualTo("COC-Workbench-Setup-1.2.1.exe");
        assertThat(entry.app().number()).isEqualTo(1);
        assertThat(entry.releases()).hasSize(2);
    }

    @Test
    void aUserCannotOpenAPrivateApp() {
        withBuilds(app(2, false), 1);
        assertNotFound(() -> service.entryForReader(2, user));
    }

    @Test
    void aUserCannotOpenAPublicAppWithNoBuilds() {
        withBuilds(app(3, true), 0);
        assertNotFound(() -> service.entryForReader(3, user));
    }

    @Test
    void anAdminOpensAnyApp() {
        withBuilds(app(4, false), 0);
        assertThat(service.entryForReader(4, admin).app().isPublic()).isFalse();
    }

    @Test
    void anUnknownNumberIsNotFoundForEveryone() {
        when(repository.findAppByNumber(99)).thenReturn(Optional.empty());
        assertNotFound(() -> service.entryForReader(99, user));
        assertNotFound(() -> service.entryForReader(99, admin));
    }
}
