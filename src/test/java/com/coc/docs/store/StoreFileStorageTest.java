package com.coc.docs.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coc.docs.web.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

/**
 * The Store's local file layout and the checks that keep every write inside
 * it (ADR-108). Plain JUnit against a temp folder — no Spring, no database.
 *
 * <p>// req: ADR-108
 */
class StoreFileStorageTest {

    @TempDir
    Path root;

    private static final UUID APP = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private StoreFileStorage storage(long maxBytes) {
        return new StoreFileStorage(root.toString(), maxBytes);
    }

    private static ByteArrayInputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void releaseLandsUnderPlatformVersionEnvironment() throws IOException {
        StoreFileStorage.StoredFile stored = storage(1024).storeRelease(
                StorePlatform.ANDROID, "1.2.0", StoreEnvironment.PRODUCTION, APP, "app.apk", bytes("hello"), () -> false);

        assertThat(stored.relativePath()).isEqualTo("android/1.2.0/production/" + APP + "/app.apk");
        assertThat(Files.readString(root.resolve(stored.relativePath()))).isEqualTo("hello");
        assertThat(stored.sizeBytes()).isEqualTo(5);
        assertThat(stored.sha256()).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    void oversizedUploadIsRefusedAndLeavesNothingBehind() throws IOException {
        assertThatThrownBy(() -> storage(4).storeRelease(
                        StorePlatform.WINDOWS, "2.0", StoreEnvironment.DEVELOPMENT, APP, "setup.exe", bytes("hello"), () -> false))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);

        try (Stream<Path> left = Files.walk(root)) {
            assertThat(left.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    void existingFileIsKeptUnlessStale() throws IOException {
        StoreFileStorage files = storage(1024);
        files.storeRelease(StorePlatform.IOS, "3.1", StoreEnvironment.PRODUCTION, APP, "a.ipa", bytes("first"), () -> false);

        assertThatThrownBy(() -> files.storeRelease(
                        StorePlatform.IOS, "3.1", StoreEnvironment.PRODUCTION, APP, "a.ipa", bytes("second"), () -> false))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.CONFLICT);
        Path file = root.resolve("ios/3.1/production/" + APP + "/a.ipa");
        assertThat(Files.readString(file)).isEqualTo("first");

        files.storeRelease(StorePlatform.IOS, "3.1", StoreEnvironment.PRODUCTION, APP, "a.ipa", bytes("second"), () -> true);
        assertThat(Files.readString(file)).isEqualTo("second");
    }

    @Test
    void versionThatCouldEscapeTheFolderIsRefused() {
        StoreFileStorage files = storage(1024);
        for (String version : new String[] {"..", "../x", "1.0/..", "a\\b", ".hidden", "1.0.", ""}) {
            assertThat(StoreFileStorage.isValidVersion(version)).as(version).isFalse();
            assertThatThrownBy(() -> files.storeRelease(
                            StorePlatform.MACOS, version, StoreEnvironment.PRODUCTION, APP, "a.dmg", bytes("x"), () -> false))
                    .isInstanceOf(ApiException.class);
        }
        assertThat(StoreFileStorage.isValidVersion("1.2.3-beta+45")).isTrue();
    }

    @Test
    void resolveRefusesPathsOutsideTheRoot() {
        StoreFileStorage files = storage(1024);
        assertThatThrownBy(() -> files.resolve("../outside.txt")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> files.resolve("android/../../outside.txt")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fileNamesAreReducedToOneSafeName() {
        assertThat(StoreFileStorage.safeFileName("../../etc/passwd")).isEqualTo("passwd");
        assertThat(StoreFileStorage.safeFileName("C:\\Users\\me\\Setup v2.exe")).isEqualTo("Setup v2.exe");
        assertThat(StoreFileStorage.safeFileName(".env")).isEqualTo("env");
        assertThat(StoreFileStorage.safeFileName("app.apk. . ")).isEqualTo("app.apk");
        assertThat(StoreFileStorage.safeFileName("앱.apk")).isEqualTo("_.apk");
        assertThat(StoreFileStorage.safeFileName("")).isEqualTo("download");
        assertThat(StoreFileStorage.safeFileName("x".repeat(300) + ".apk")).hasSize(StoreFileStorage.MAX_FILE_NAME).endsWith(".apk");
    }

    @Test
    void logoMustBeTheImageTypeItClaims() throws IOException {
        StoreFileStorage files = storage(1024);
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

        StoreFileStorage.StoredFile stored = files.storeLogo(APP, "image/png", new ByteArrayInputStream(png));
        assertThat(stored.relativePath()).isEqualTo("logos/" + APP + ".png");

        assertThatThrownBy(() -> files.storeLogo(APP, "image/jpeg", new ByteArrayInputStream(png)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> files.storeLogo(APP, "image/svg+xml", bytes("<svg/>")))
                .isInstanceOf(ApiException.class);
        assertThat(Files.exists(root.resolve("logos/" + APP + ".jpg"))).isFalse();
    }

    @Test
    void deleteRemovesTheFileAndTheFoldersItEmptied() throws IOException {
        StoreFileStorage files = storage(1024);
        StoreFileStorage.StoredFile stored = files.storeRelease(
                StorePlatform.ANDROID, "9.9", StoreEnvironment.DEVELOPMENT, APP, "b.apk", bytes("x"), () -> false);

        files.delete(stored.relativePath());

        assertThat(Files.exists(root.resolve("android"))).isFalse();
        assertThat(Files.exists(root)).isTrue();
    }

    @Test
    void platformsAcceptOnlyTheirInstallFiles() {
        assertThat(StorePlatform.ANDROID.accepts("app.APK")).isTrue();
        assertThat(StorePlatform.ANDROID.accepts("app.ipa")).isFalse();
        assertThat(StorePlatform.IOS.accepts("app.ipa")).isTrue();
        assertThat(StorePlatform.WINDOWS.accepts("setup.msi")).isTrue();
        assertThat(StorePlatform.MACOS.accepts("App.dmg")).isTrue();
        assertThat(StorePlatform.MACOS.accepts("index.html")).isFalse();
        assertThat(StorePlatform.MACOS.accepts("noextension")).isFalse();
    }
}
