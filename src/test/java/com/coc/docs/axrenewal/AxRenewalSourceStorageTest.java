package com.coc.docs.axrenewal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coc.docs.storage.FileInfoProperties;
import com.coc.docs.web.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The uploaded source archive on disk (ADR-118). */
class AxRenewalSourceStorageTest {

    private static final byte[] ZIP = {'P', 'K', 3, 4, 0, 0, 0, 0};

    @TempDir
    Path dir;

    private AxRenewalSourceStorage storage(Path path) {
        return new AxRenewalSourceStorage(new FileInfoProperties(path == null ? null : path.toString()));
    }

    @Test
    void storesTheArchiveUnderAServerChosenNameAndCreatesTheFolder() throws IOException {
        Path target = dir.resolve("not-there-yet");

        String name = storage(target).store(new ByteArrayInputStream(ZIP));

        assertThat(name).matches("[0-9a-f-]{36}[.]zip");
        assertThat(Files.readAllBytes(target.resolve(name))).isEqualTo(ZIP);
        try (var files = Files.list(target)) {
            assertThat(files).hasSize(1); // no .part left behind
        }
    }

    @Test
    void refusesSomethingThatIsNotAZipAndLeavesNothingBehind() throws IOException {
        assertThatThrownBy(() -> storage(dir).store(new ByteArrayInputStream("hello".getBytes())))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> storage(dir).store(new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(ApiException.class);
        try (var files = Files.list(dir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void refusesWhenNoFolderIsConfigured() {
        assertThatThrownBy(() -> storage(null).store(new ByteArrayInputStream(ZIP)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void deletesOnlyAFileDirectlyInsideItsFolder() throws IOException {
        Path outside = Files.writeString(dir.resolve("outside.txt"), "keep");
        Path inner = Files.createDirectory(dir.resolve("inner"));
        AxRenewalSourceStorage storage = storage(inner);
        String name = storage.store(new ByteArrayInputStream(ZIP));

        storage.delete("../outside.txt");
        storage.delete(name);
        storage.delete(null);

        assertThat(outside).exists();
        assertThat(inner.resolve(name)).doesNotExist();
    }
}
