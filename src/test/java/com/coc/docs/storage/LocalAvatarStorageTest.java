package com.coc.docs.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coc.docs.web.ApiException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

/** Profile photos on local disk (ADR-119). */
class LocalAvatarStorageTest {

    @TempDir
    Path dir;

    private static byte[] png() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    @Test
    void storesThePhotoAndReturnsItsFileNameOnly() throws IOException {
        Path target = dir.resolve("not-there-yet");
        LocalAvatarStorage storage = new LocalAvatarStorage(target.toString());

        String name = storage.upload(UUID.randomUUID(), new MockMultipartFile("file", "me.png", "image/png", png()));

        assertThat(name).matches("[0-9a-f-]{36}[.]png");
        assertThat(LocalAvatarStorage.isStoredName(name)).isTrue();
        assertThat(storage.find(name)).contains(target.resolve(name));
        assertThat(Files.readAllBytes(target.resolve(name))).isEqualTo(png());
    }

    @Test
    void refusesWhatIsNotReallyAnImage() {
        LocalAvatarStorage storage = new LocalAvatarStorage(dir.toString());
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() ->
                        storage.upload(id, new MockMultipartFile("file", "x.png", "image/png", "nope".getBytes())))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() ->
                        storage.upload(id, new MockMultipartFile("file", "x.txt", "text/plain", "nope".getBytes())))
                .isInstanceOf(ApiException.class);
        assertThat(dir).isEmptyDirectory();
    }

    @Test
    void deletesItsOwnFilesAndIgnoresEverythingElse() throws IOException {
        Path inner = Files.createDirectory(dir.resolve("inner"));
        Path outside = Files.writeString(dir.resolve("outside.png"), "keep");
        LocalAvatarStorage storage = new LocalAvatarStorage(inner.toString());
        String name = storage.upload(UUID.randomUUID(), new MockMultipartFile("file", "me.png", "image/png", png()));

        storage.delete("../outside.png");
        storage.delete("https://lh3.googleusercontent.com/a/photo");
        storage.delete(null);
        assertThat(storage.find("../outside.png")).isEmpty();
        assertThat(outside).exists();

        storage.delete(name);
        assertThat(storage.find(name)).isEmpty();
    }
}
