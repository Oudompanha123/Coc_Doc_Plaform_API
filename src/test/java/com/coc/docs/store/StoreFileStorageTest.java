package com.coc.docs.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coc.docs.web.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

/**
 * The Store's files on disk — builds and logos — and the checks that keep
 * every write inside the store root (ADR-108, ADR-111). Plain JUnit against a
 * temp folder — no Spring, no database.
 *
 * <p>// req: ADR-108, ADR-111
 */
class StoreFileStorageTest {

    @TempDir
    Path root;

    private static final UUID APP = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0};

    private StoreFileStorage storage(long maxBytes) {
        return new StoreFileStorage(root.toString(), maxBytes);
    }

    private static ByteArrayInputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Stream<Path> filesUnder(Path dir) throws IOException {
        return Files.walk(dir).filter(Files::isRegularFile);
    }

    // --- builds -----------------------------------------------------------------

    @Test
    void buildLandsUnderPlatformVersionEnvironment() throws IOException {
        StoreFileStorage.StoredBuild stored = storage(1024).storeBuild(StorePlatform.ANDROID, "1.2.0",
                List.of(StoreEnvironment.PRODUCTION), APP, "app.apk", bytes("hello"), env -> false);

        String path = stored.paths().get(StoreEnvironment.PRODUCTION);
        assertThat(path).isEqualTo("android/1.2.0/production/" + APP + "/app.apk");
        assertThat(Files.readString(root.resolve(path))).isEqualTo("hello");
        assertThat(stored.sizeBytes()).isEqualTo(5);
        assertThat(stored.sha256()).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    void oneUploadToBothEnvironmentsGivesEachItsOwnRemovableFile() throws IOException {
        StoreFileStorage files = storage(1024);
        StoreFileStorage.StoredBuild stored = files.storeBuild(StorePlatform.IOS, "3.0",
                List.of(StoreEnvironment.PRODUCTION, StoreEnvironment.DEVELOPMENT), APP, "app.ipa", bytes("both"),
                env -> false);

        Path production = root.resolve(stored.paths().get(StoreEnvironment.PRODUCTION));
        Path development = root.resolve(stored.paths().get(StoreEnvironment.DEVELOPMENT));
        assertThat(Files.readString(production)).isEqualTo("both");
        assertThat(Files.readString(development)).isEqualTo("both");

        files.delete(stored.paths().get(StoreEnvironment.PRODUCTION));

        assertThat(Files.exists(production)).isFalse();
        assertThat(Files.readString(development)).isEqualTo("both");
    }

    @Test
    void anExistingBuildIsKeptUnlessStaleAndNothingIsWritten() throws IOException {
        StoreFileStorage files = storage(1024);
        files.storeBuild(StorePlatform.WINDOWS, "1.0", List.of(StoreEnvironment.PRODUCTION), APP, "a.exe",
                bytes("first"), env -> false);

        assertThatThrownBy(() -> files.storeBuild(StorePlatform.WINDOWS, "1.0",
                        List.of(StoreEnvironment.DEVELOPMENT, StoreEnvironment.PRODUCTION), APP, "a.exe",
                        bytes("second"), env -> false))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(Files.readString(root.resolve("windows/1.0/production/" + APP + "/a.exe"))).isEqualTo("first");
        assertThat(Files.exists(root.resolve("windows/1.0/development/" + APP + "/a.exe"))).isFalse();

        files.storeBuild(StorePlatform.WINDOWS, "1.0", List.of(StoreEnvironment.PRODUCTION), APP, "a.exe",
                bytes("second"), env -> true);
        assertThat(Files.readString(root.resolve("windows/1.0/production/" + APP + "/a.exe"))).isEqualTo("second");
    }

    @Test
    void oversizedOrEmptyBuildIsRefusedAndLeavesNothingBehind() throws IOException {
        assertThatThrownBy(() -> storage(4).storeBuild(StorePlatform.MACOS, "2.0",
                        List.of(StoreEnvironment.PRODUCTION), APP, "a.dmg", bytes("hello"), env -> false))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThatThrownBy(() -> storage(4).storeBuild(StorePlatform.MACOS, "2.0",
                        List.of(StoreEnvironment.PRODUCTION), APP, "a.dmg", bytes(""), env -> false))
                .isInstanceOf(ApiException.class);
        try (Stream<Path> left = filesUnder(root)) {
            assertThat(left).isEmpty();
        }
    }

    @Test
    void aVersionThatCouldEscapeTheFolderIsRefused() {
        StoreFileStorage files = storage(1024);
        for (String version : new String[] {"..", "../x", "1.0/..", "a\\b", ".hidden", "1.0.", ""}) {
            assertThat(StoreFileStorage.isValidVersion(version)).as(version).isFalse();
            assertThatThrownBy(() -> files.storeBuild(StorePlatform.ANDROID, version,
                            List.of(StoreEnvironment.PRODUCTION), APP, "a.apk", bytes("x"), env -> false))
                    .isInstanceOf(ApiException.class);
        }
        assertThat(StoreFileStorage.isValidVersion("1.2.3-beta+45")).isTrue();
    }

    @Test
    void removingTheLastBuildOfAVersionLeavesNoEmptyFolders() throws IOException {
        StoreFileStorage files = storage(1024);
        StoreFileStorage.StoredBuild stored = files.storeBuild(StorePlatform.ANDROID, "9.9",
                List.of(StoreEnvironment.DEVELOPMENT), APP, "b.apk", bytes("x"), env -> false);

        files.delete(stored.paths().get(StoreEnvironment.DEVELOPMENT));

        assertThat(Files.exists(root.resolve("android"))).isFalse();
        assertThat(Files.exists(root)).isTrue();
    }

    @Test
    void fileNamesAreReducedToOneSafeName() {
        assertThat(StoreFileStorage.safeFileName("../../etc/passwd")).isEqualTo("passwd");
        assertThat(StoreFileStorage.safeFileName("C:\\Users\\me\\Setup v2.exe")).isEqualTo("Setup v2.exe");
        assertThat(StoreFileStorage.safeFileName(".env")).isEqualTo("env");
        assertThat(StoreFileStorage.safeFileName("app.apk. . ")).isEqualTo("app.apk");
        assertThat(StoreFileStorage.safeFileName("")).isEqualTo("download");
        assertThat(StoreFileStorage.safeFileName("x".repeat(300) + ".apk"))
                .hasSize(StoreFileStorage.MAX_FILE_NAME)
                .endsWith(".apk");
    }

    @Test
    void theFileTypeNamesThePlatform() {
        assertThat(StorePlatform.ofFile("app.APK")).contains(StorePlatform.ANDROID);
        assertThat(StorePlatform.ofFile("bundle.aab")).contains(StorePlatform.ANDROID);
        assertThat(StorePlatform.ofFile("app.ipa")).contains(StorePlatform.IOS);
        assertThat(StorePlatform.ofFile("Setup.msi")).contains(StorePlatform.WINDOWS);
        assertThat(StorePlatform.ofFile("App.dmg")).contains(StorePlatform.MACOS);
        assertThat(StorePlatform.ofFile("app.zip")).isEmpty();
        assertThat(StorePlatform.ofFile("index.html")).isEmpty();
        assertThat(StorePlatform.ofFile("noextension")).isEmpty();
    }

    // --- logos ------------------------------------------------------------------

    @Test
    void logoMustBeTheImageTypeItClaims() throws IOException {
        StoreFileStorage files = storage(1024);
        StoreFileStorage.StoredFile stored = files.storeLogo(APP, "image/png", new ByteArrayInputStream(PNG));
        assertThat(stored.relativePath()).isEqualTo("logos/" + APP + ".png");

        assertThatThrownBy(() -> files.storeLogo(APP, "image/jpeg", new ByteArrayInputStream(PNG)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> files.storeLogo(APP, "image/svg+xml", bytes("<svg/>")))
                .isInstanceOf(ApiException.class);
        assertThat(Files.exists(root.resolve("logos/" + APP + ".jpg"))).isFalse();
    }

    @Test
    void replacingALogoWithAnotherTypeRemovesTheOldFile() throws IOException {
        StoreFileStorage files = storage(1024);
        files.storeLogo(APP, "image/png", new ByteArrayInputStream(PNG));

        files.storeLogo(APP, "image/jpeg", new ByteArrayInputStream(JPEG));

        assertThat(Files.exists(root.resolve("logos/" + APP + ".jpg"))).isTrue();
        assertThat(Files.exists(root.resolve("logos/" + APP + ".png"))).isFalse();
    }

    @Test
    void resolveRefusesPathsOutsideTheRoot() {
        StoreFileStorage files = storage(1024);
        assertThatThrownBy(() -> files.resolve("../outside.txt")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> files.resolve("android/../../outside.txt")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> files.resolve(".")).isInstanceOf(IllegalArgumentException.class);
    }
}
