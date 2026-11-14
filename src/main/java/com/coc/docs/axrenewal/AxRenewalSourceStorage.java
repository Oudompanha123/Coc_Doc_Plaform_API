package com.coc.docs.axrenewal;

import com.coc.docs.storage.FileInfoProperties;
import com.coc.docs.web.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Keeps a project's uploaded source archive on this backend's own disk, under
 * {@code file.server-path} (ADR-118).
 *
 * <p>One zip per project, named {@code <uuid>.zip} — never the name the
 * browser sent, so nothing a caller types can choose where a file lands. The
 * caller stores that name in {@code ax_renewal_project.source_filename}.
 *
 * <p>// req: ADR-118
 */
@Component
public class AxRenewalSourceStorage {

    private static final Logger log = LoggerFactory.getLogger(AxRenewalSourceStorage.class);

    /** The largest archive accepted. A project's own source, with dependency trees and build output already left out. */
    static final long MAX_BYTES = 200L * 1024 * 1024;

    /** Every zip starts with a local file header ({@code PK\3\4}); an empty one with the end record ({@code PK\5\6}). */
    private static final byte[] ZIP_MAGIC = {'P', 'K'};

    private final FileInfoProperties properties;

    public AxRenewalSourceStorage(FileInfoProperties properties) {
        this.properties = properties;
    }

    /**
     * Streams {@code body} to a new file and returns its name.
     *
     * <p>Written under a temporary name first and moved into place only once
     * it is complete, so a dropped connection or an oversized upload never
     * leaves a half-written archive behind under a name somebody might store.
     */
    public String store(InputStream body) throws IOException {
        Path dir = directory();
        Files.createDirectories(dir);

        String fileName = UUID.randomUUID() + ".zip";
        Path partial = dir.resolve(fileName + ".part");
        try {
            long written = copyCapped(body, partial);
            if (written == 0) {
                throw ApiException.badRequest("the upload is empty");
            }
            Files.move(partial, dir.resolve(fileName), StandardCopyOption.ATOMIC_MOVE);
            return fileName;
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private static long copyCapped(InputStream body, Path target) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        try (OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = body.read(buffer)) != -1) {
                if (total == 0 && (read < 2 || buffer[0] != ZIP_MAGIC[0] || buffer[1] != ZIP_MAGIC[1])) {
                    throw ApiException.badRequest("the upload is not a zip archive");
                }
                total += read;
                if (total > MAX_BYTES) {
                    throw ApiException.badRequest(
                            "the project's source is larger than " + (MAX_BYTES / (1024 * 1024)) + " MB");
                }
                out.write(buffer, 0, read);
            }
        }
        return total;
    }

    /**
     * Removes a stored archive. Best effort: the file is no longer referenced
     * by the time this is called, so a failure is logged, not thrown — it
     * must not turn a successful upload or delete into an error.
     */
    public void delete(String fileName) {
        if (fileName == null || fileName.isBlank() || !properties.configured()) {
            return;
        }
        try {
            Path dir = directory();
            Path file = dir.resolve(fileName).normalize();
            // The name comes from our own column, but a file outside the
            // folder is never ours to delete, whatever the column says.
            if (!file.getParent().equals(dir)) {
                log.warn("Refusing to delete {}: it is not directly inside {}", fileName, dir);
                return;
            }
            Files.deleteIfExists(file);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not delete the stored source {}", fileName, e);
        }
    }

    private Path directory() {
        if (!properties.configured()) {
            throw ApiException.badRequest("Uploading a project's source is not available on this deployment yet.");
        }
        return Path.of(properties.serverPath().trim()).toAbsolutePath().normalize();
    }
}
