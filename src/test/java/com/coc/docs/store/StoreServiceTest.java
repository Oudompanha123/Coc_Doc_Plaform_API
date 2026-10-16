package com.coc.docs.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.coc.docs.account.AppRole;
import com.coc.docs.audit.AuditAction;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The Store's apps and builds (ADR-108, ADR-111): who sees what, one app per
 * name, the next version going to the existing app on the platform its file
 * names, duplicate versions per environment, soft removal, and audit.
 *
 * <p>// req: ADR-108, ADR-111
 */
class StoreServiceTest {

    private StoreRepository repository;
    private StoreFiles files;
    private AuditRepository audit;
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
        files = mock(StoreFiles.class);
        audit = mock(AuditRepository.class);
        service = new StoreService(repository, files, audit, new ObjectMapper());
    }

    private StoreApp app(long number, boolean isPublic) {
        StoreApp app = new StoreApp(UUID.randomUUID(), number, "Change Impact Preview", "", StoreCategory.MOBILE, isPublic, null, null,
                Instant.now(), Instant.now());
        when(repository.findAppByNumber(number)).thenReturn(Optional.of(app));
        when(repository.findApp(app.id())).thenReturn(Optional.of(app));
        return app;
    }

    private StoreBuild build(StoreApp app, StorePlatform platform, String version, StoreEnvironment env, Instant removedAt) {
        StoreBuild build = new StoreBuild(UUID.randomUUID(), app.id(), platform, version, env, "", "a.apk",
                platform.wire() + "/" + version + "/" + env.wire() + "/" + app.id() + "/a.apk", 1, "0".repeat(64),
                Instant.now(), removedAt, "Admin");
        when(repository.findBuild(build.id())).thenReturn(Optional.of(build));
        return build;
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(status);
    }

    private StoreService.CatalogEntry upload(StoreApp app, String version, List<String> envs, String fileName) {
        return service.uploadBuild(app.id(), version, envs, "What's new", fileName,
                new ByteArrayInputStream(new byte[] {1}), admin);
    }

    // --- who sees what ---------------------------------------------------------

    @Test
    void aUserOpensAPublicApp() {
        app(1, true);
        assertThat(service.entryForReader(1, user).app().number()).isEqualTo(1);
    }

    @Test
    void aUserCannotOpenAPrivateAppButAnAdminCan() {
        app(2, false);
        assertStatus(() -> service.entryForReader(2, user), HttpStatus.NOT_FOUND);
        assertThat(service.entryForReader(2, admin).app().isPublic()).isFalse();
    }

    @Test
    void aVisitorWithNoSessionSeesAPublicAppButNotAPrivateOne() {
        app(5, true);
        app(6, false);
        assertThat(service.entryForReader(5, null).app().number()).isEqualTo(5);
        assertStatus(() -> service.entryForReader(6, null), HttpStatus.NOT_FOUND);
    }

    @Test
    void aRemovedBuildCannotBeDownloadedEvenByAnAdmin() {
        StoreApp app = app(3, true);
        StoreBuild gone = build(app, StorePlatform.ANDROID, "1.0", StoreEnvironment.PRODUCTION, Instant.now());
        assertStatus(() -> service.download(gone.id(), admin), HttpStatus.NOT_FOUND);
    }

    @Test
    void aPrivateAppsBuildIsNotFoundForAUser() {
        StoreApp app = app(4, false);
        StoreBuild live = build(app, StorePlatform.ANDROID, "1.0", StoreEnvironment.PRODUCTION, null);
        assertStatus(() -> service.download(live.id(), user), HttpStatus.NOT_FOUND);
    }

    // --- Add App ----------------------------------------------------------------

    @Test
    void addingAnAppTakesANameOnlyAndIsAudited() {
        UUID id = UUID.randomUUID();
        when(repository.insertApp(eq("Change Impact Preview"), eq(""), eq(StoreCategory.MOBILE), eq(false), any())).thenReturn(id);
        when(repository.findApp(id)).thenReturn(Optional.of(new StoreApp(id, 394, "Change Impact Preview", "",
                StoreCategory.MOBILE, false, null, null, Instant.now(), Instant.now())));

        assertThat(service.createApp(" Change Impact Preview ", null, "mobile", false, admin).app().number()).isEqualTo(394);
        verify(audit).append(any(), any(), eq(AuditAction.STORE_APP_CREATE), eq(AuditAction.TARGET_STORE_APP), eq(id), any());
    }

    @Test
    void aSecondAppWithTheSameNameIsRefused() {
        when(repository.appNameTaken("Change Impact Preview", null)).thenReturn(true);

        assertStatus(() -> service.createApp("Change Impact Preview", "", "mobile", true, admin), HttpStatus.CONFLICT);
        verify(repository, never()).insertApp(any(), any(), any(), anyBoolean(), any());
    }

    // --- Next version -----------------------------------------------------------

    @Test
    void theNextVersionGoesToTheExistingAppOnThePlatformItsFileNames() {
        StoreApp app = app(5, true);
        when(files.storeBuild(eq(StorePlatform.ANDROID), eq("1.2.2"), eq(List.of(StoreEnvironment.PRODUCTION)),
                        eq(app.id()), eq("app-1.2.2.apk"), any(), any()))
                .thenReturn(new StoreFiles.StoredBuild(
                        Map.of(StoreEnvironment.PRODUCTION, "android/1.2.2/production/x/app-1.2.2.apk"), 1, "0".repeat(64)));

        upload(app, "1.2.2", List.of("production"), "app-1.2.2.apk");

        verify(repository).insertBuild(eq(app.id()), eq(StorePlatform.ANDROID), eq("1.2.2"),
                eq(StoreEnvironment.PRODUCTION), eq("What's new"), eq("app-1.2.2.apk"), anyString(), anyLong(),
                anyString(), any());
        verify(repository, never()).insertApp(any(), any(), any(), anyBoolean(), any());
        verify(audit).append(any(), any(), eq(AuditAction.STORE_BUILD_UPLOAD), eq(AuditAction.TARGET_STORE_APP),
                eq(app.id()), any());
    }

    @Test
    void tickingBothServerTypesPublishesOneFileAsTwoBuilds() {
        StoreApp app = app(6, true);
        List<StoreEnvironment> both = List.of(StoreEnvironment.PRODUCTION, StoreEnvironment.DEVELOPMENT);
        when(files.storeBuild(eq(StorePlatform.IOS), eq("2.0"), eq(both), eq(app.id()), eq("app.ipa"), any(), any()))
                .thenReturn(new StoreFiles.StoredBuild(Map.of(
                        StoreEnvironment.PRODUCTION, "ios/2.0/production/x/app.ipa",
                        StoreEnvironment.DEVELOPMENT, "ios/2.0/development/x/app.ipa"), 1, "0".repeat(64)));

        upload(app, "2.0", List.of("production", "development"), "app.ipa");

        verify(repository).insertBuild(eq(app.id()), eq(StorePlatform.IOS), eq("2.0"), eq(StoreEnvironment.PRODUCTION),
                any(), any(), eq("ios/2.0/production/x/app.ipa"), anyLong(), anyString(), any());
        verify(repository).insertBuild(eq(app.id()), eq(StorePlatform.IOS), eq("2.0"), eq(StoreEnvironment.DEVELOPMENT),
                any(), any(), eq("ios/2.0/development/x/app.ipa"), anyLong(), anyString(), any());
    }

    @Test
    void aVersionAlreadyLiveInAChosenEnvironmentIsRefusedBeforeAnyFileIsWritten() {
        StoreApp app = app(7, true);
        when(repository.liveBuildExists(app.id(), StorePlatform.ANDROID, "1.2.1", StoreEnvironment.DEVELOPMENT))
                .thenReturn(true);

        assertStatus(() -> upload(app, "1.2.1", List.of("production", "development"), "a.apk"), HttpStatus.CONFLICT);
        verifyNoInteractions(files);
    }

    @Test
    void theSameVersionOnAnotherPlatformIsAllowed() {
        StoreApp app = app(8, true);
        when(repository.liveBuildExists(app.id(), StorePlatform.ANDROID, "1.0", StoreEnvironment.PRODUCTION)).thenReturn(true);
        when(files.storeBuild(eq(StorePlatform.IOS), eq("1.0"), any(), eq(app.id()), any(), any(), any()))
                .thenReturn(new StoreFiles.StoredBuild(
                        Map.of(StoreEnvironment.PRODUCTION, "ios/1.0/production/x/a.ipa"), 1, "0".repeat(64)));

        upload(app, "1.0", List.of("production"), "a.ipa");

        verify(repository).insertBuild(eq(app.id()), eq(StorePlatform.IOS), eq("1.0"), eq(StoreEnvironment.PRODUCTION),
                any(), any(), any(), anyLong(), anyString(), any());
    }

    @Test
    void anUploadIsRefusedWithoutAServerTypeAFileTypeOrAVersion() {
        StoreApp app = app(9, true);
        assertStatus(() -> upload(app, "1.0", List.of(), "a.apk"), HttpStatus.BAD_REQUEST);
        assertStatus(() -> upload(app, "1.0", List.of("staging"), "a.apk"), HttpStatus.BAD_REQUEST);
        assertStatus(() -> upload(app, "1.0", List.of("production"), "a.zip"), HttpStatus.BAD_REQUEST);
        assertStatus(() -> upload(app, "../1", List.of("production"), "a.apk"), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.uploadBuild(app.id(), "1.0", List.of("production"), "x".repeat(1501), "a.apk",
                new ByteArrayInputStream(new byte[] {1}), admin), HttpStatus.BAD_REQUEST);
        verifyNoInteractions(files);
    }

    // --- removing ---------------------------------------------------------------

    @Test
    void removingABuildKeepsItsRowTouchesNoOtherBuildAndIsAudited() {
        StoreApp app = app(10, true);
        StoreBuild other = build(app, StorePlatform.ANDROID, "1.0", StoreEnvironment.PRODUCTION, null);
        StoreBuild target = build(app, StorePlatform.ANDROID, "1.1", StoreEnvironment.PRODUCTION, null);
        when(repository.markBuildRemoved(eq(target.id()), any())).thenReturn(true);

        service.removeBuild(app.id(), target.id(), admin);

        verify(files).delete(target.filePath());
        verify(files, never()).delete(other.filePath());
        verify(audit).append(any(), any(), eq(AuditAction.STORE_BUILD_REMOVE), eq(AuditAction.TARGET_STORE_APP),
                eq(app.id()), any());
    }

    @Test
    void removingABuildStillSucceedsWhenItsFileCannotBeDeleted() {
        StoreApp app = app(13, true);
        StoreBuild target = build(app, StorePlatform.ANDROID, "1.1.1", StoreEnvironment.PRODUCTION, null);
        when(repository.markBuildRemoved(eq(target.id()), any())).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("disk gone")).when(files).delete(target.filePath());

        service.removeBuild(app.id(), target.id(), admin);

        verify(repository).markBuildRemoved(eq(target.id()), any());
        verify(audit).append(any(), any(), eq(AuditAction.STORE_BUILD_REMOVE), eq(AuditAction.TARGET_STORE_APP),
                eq(app.id()), any());
    }

    @Test
    void aRemovedBuildCannotBeDownloadedByAUserOrAVisitor() {
        StoreApp app = app(14, true);
        StoreBuild gone = build(app, StorePlatform.ANDROID, "1.1.1", StoreEnvironment.PRODUCTION, Instant.now());
        assertStatus(() -> service.download(gone.id(), user), HttpStatus.NOT_FOUND);
        assertStatus(() -> service.download(gone.id(), null), HttpStatus.NOT_FOUND);
        verify(files, never()).deliver(any(), any(), any());
    }

    @Test
    void aLiveBuildWhoseFileIsMissingIsNotFoundRatherThanAnError() {
        StoreApp app = app(15, true);
        StoreBuild live = build(app, StorePlatform.ANDROID, "1.1.2", StoreEnvironment.PRODUCTION, null);
        when(files.deliver(eq(live.filePath()), any(), any())).thenThrow(com.coc.docs.web.ApiException.notFound("build file"));
        assertStatus(() -> service.download(live.id(), user), HttpStatus.NOT_FOUND);
    }

    @Test
    void removingAnAlreadyRemovedBuildIsAConflict() {
        StoreApp app = app(11, true);
        StoreBuild gone = build(app, StorePlatform.IOS, "1.0", StoreEnvironment.DEVELOPMENT, Instant.now());

        assertStatus(() -> service.removeBuild(app.id(), gone.id(), admin), HttpStatus.CONFLICT);
        verifyNoInteractions(files);
    }

    @Test
    void deletingAnAppRemovesTheFilesOfItsLiveBuilds() {
        StoreApp app = app(12, true);
        StoreBuild live = build(app, StorePlatform.ANDROID, "1.0", StoreEnvironment.PRODUCTION, null);
        StoreBuild gone = build(app, StorePlatform.ANDROID, "0.9", StoreEnvironment.PRODUCTION, Instant.now());
        when(repository.findBuilds(List.of(app.id()))).thenReturn(List.of(live, gone));

        service.deleteApp(app.id(), admin);

        verify(repository).deleteApp(app.id());
        verify(files).delete(live.filePath());
        verify(files, never()).delete(gone.filePath());
    }
}
