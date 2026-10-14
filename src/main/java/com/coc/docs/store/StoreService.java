package com.coc.docs.store;

import com.coc.docs.account.AppRole;
import com.coc.docs.audit.AuditAction;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Store page (ADR-108): apps, their builds, and the files behind them.
 *
 * <p><b>Two audiences.</b> {@link #publicCatalog} is what every signed-in
 * user sees — public apps that have at least one build. {@link #fullCatalog}
 * and the write methods are for admins; {@code SecurityConfig} decides who
 * reaches them, by path, per ADR-011. The one place this class looks at the
 * caller's role itself is {@link #download}: the route is open to every
 * user, and only an admin may fetch a private app's build through it (to
 * check it before making the app public).
 *
 * <p><b>Database and disk are kept in step by order, not by a shared
 * transaction.</b> A file is written before its row is inserted and removed
 * after its row is deleted, so a failure in between leaves at worst an
 * unreferenced file — never a row pointing at nothing, which would be a
 * broken download button.
 *
 * <p>// req: ADR-108, NFR-OPS-AUDIT
 */
@Service
public class StoreService {

    static final int MAX_NAME = 80;
    static final int MAX_TEXT = 2000;

    /**
     * Release notes travel in the upload's query string — the body is the
     * file itself — so they are held well under the request-line limit even
     * when every character is multi-byte (Korean, Khmer).
     */
    static final int MAX_NOTES = 500;

    private final StoreRepository store;
    private final StoreFileStorage files;
    private final AuditRepository audit;
    private final ObjectMapper json;

    public StoreService(StoreRepository store, StoreFileStorage files, AuditRepository audit, ObjectMapper json) {
        this.store = store;
        this.files = files;
        this.audit = audit;
        this.json = json;
    }

    /** An app with its builds, newest first. */
    public record CatalogEntry(StoreApp app, List<StoreRelease> releases) {}

    /** A file to send: where it is on disk, what to call it, and its content type. */
    public record Download(Path path, String fileName, String contentType, long sizeBytes) {}

    @Transactional(readOnly = true)
    public List<CatalogEntry> publicCatalog() {
        return catalog(store.findApps(true)).stream().filter(e -> !e.releases().isEmpty()).toList();
    }

    @Transactional(readOnly = true)
    public List<CatalogEntry> fullCatalog() {
        return catalog(store.findApps(false));
    }

    @Transactional(readOnly = true)
    public CatalogEntry entry(UUID appId) {
        StoreApp app = app(appId);
        return new CatalogEntry(app, store.findReleases(List.of(appId)));
    }

    /**
     * One app's page ({@code /store-<number>}). Shows a user exactly what the
     * list would — a public app with at least one build — and answers 404 for
     * anything else, so a private app's number cannot be confirmed by trying
     * it. An admin sees every app, as on the admin list.
     */
    @Transactional(readOnly = true)
    public CatalogEntry entryForReader(long number, CurrentUser caller) {
        StoreApp app = store.findAppByNumber(number).orElseThrow(() -> ApiException.notFound("app"));
        List<StoreRelease> releases = store.findReleases(List.of(app.id()));
        boolean admin = caller.role().isAtLeast(AppRole.ADMIN);
        if (!admin && (!app.isPublic() || releases.isEmpty())) {
            throw ApiException.notFound("app");
        }
        return new CatalogEntry(app, releases);
    }

    private List<CatalogEntry> catalog(List<StoreApp> apps) {
        Map<UUID, List<StoreRelease>> byApp = store.findReleases(apps.stream().map(StoreApp::id).toList()).stream()
                .collect(Collectors.groupingBy(StoreRelease::appId));
        return apps.stream().map(a -> new CatalogEntry(a, byApp.getOrDefault(a.id(), List.of()))).toList();
    }

    @Transactional
    public CatalogEntry createApp(String name, String description, String platform, boolean isPublic, CurrentUser caller) {
        StorePlatform parsed = StorePlatform.parse(platform)
                .orElseThrow(() -> ApiException.badRequest("the platform must be android, ios, windows or macos"));
        String cleanName = cleanName(name);
        String cleanDescription = cleanText(description, "the description");
        UUID id = store.insertApp(cleanName, cleanDescription, parsed, isPublic, caller.id());
        record(caller, AuditAction.STORE_APP_CREATE, id, detail(cleanName).put("platform", parsed.wire()).put("public", isPublic));
        return entry(id);
    }

    @Transactional
    public CatalogEntry updateApp(UUID id, String name, String description, boolean isPublic, CurrentUser caller) {
        StoreApp current = app(id);
        String cleanName = cleanName(name);
        String cleanDescription = cleanText(description, "the description");
        store.updateApp(id, cleanName, cleanDescription, isPublic, caller.id());
        ObjectNode detail = detail(cleanName).put("public", isPublic);
        if (current.isPublic() != isPublic) {
            detail.put("visibilityChanged", true);
        }
        record(caller, AuditAction.STORE_APP_UPDATE, id, detail);
        return entry(id);
    }

    /** Removes the app, every build of it, and every file behind them. */
    @Transactional
    public void deleteApp(UUID id, CurrentUser caller) {
        StoreApp app = app(id);
        List<StoreRelease> releases = store.findReleases(List.of(id));
        store.deleteApp(id);
        record(caller, AuditAction.STORE_APP_DELETE, id, detail(app.name()).put("releases", releases.size()));
        // After the rows: a crash here leaves stray files, not dead buttons.
        releases.forEach(r -> files.delete(r.filePath()));
        if (app.hasLogo()) {
            files.delete(app.logoPath());
        }
    }

    public CatalogEntry uploadLogo(UUID id, String contentType, InputStream body, CurrentUser caller) {
        app(id);
        String type = contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        StoreFileStorage.StoredFile stored = files.storeLogo(id, type, body);
        store.setLogo(id, stored.relativePath(), type, caller.id());
        return entry(id);
    }

    /**
     * Streams a build to disk and records it. Not {@code @Transactional}: the
     * upload can take minutes, and a database connection should not be held
     * open — or a row locked — for the length of somebody's network.
     */
    public CatalogEntry uploadRelease(
            UUID appId,
            String version,
            String environment,
            String notes,
            String originalFileName,
            InputStream body,
            CurrentUser caller) {
        StoreApp app = app(appId);
        String cleanVersion = version == null ? "" : version.trim();
        if (!StoreFileStorage.isValidVersion(cleanVersion)) {
            throw ApiException.badRequest(
                    "the version must start and end with a letter or digit and use only letters, digits, '.', '_', '+' and '-'");
        }
        StoreEnvironment env = StoreEnvironment.parse(environment)
                .orElseThrow(() -> ApiException.badRequest("the environment must be production or development"));
        String cleanNotes = cleanText(notes, "the release notes", MAX_NOTES);
        String fileName = StoreFileStorage.safeFileName(originalFileName);
        if (!app.platform().accepts(fileName)) {
            throw ApiException.badRequest("a " + app.platform().wire() + " build must be one of: ."
                    + String.join(", .", app.platform().extensions().stream().sorted().toList()));
        }
        if (store.releaseExists(appId, cleanVersion, env)) {
            throw ApiException.conflict("this app already has a " + env.wire() + " build of version " + cleanVersion
                    + " — delete it first to replace it");
        }

        StoreFileStorage.StoredFile stored = files.storeRelease(
                app.platform(), cleanVersion, env, appId, fileName, body,
                () -> !store.releaseExists(appId, cleanVersion, env));
        try {
            store.insertRelease(appId, cleanVersion, env, cleanNotes, fileName, stored.relativePath(),
                    stored.sizeBytes(), stored.sha256(), caller.id());
        } catch (DuplicateKeyException e) {
            files.delete(stored.relativePath());
            throw ApiException.conflict("this app already has a " + env.wire() + " build of version " + cleanVersion);
        }
        store.touchApp(appId, caller.id());
        record(caller, AuditAction.STORE_RELEASE_UPLOAD, appId, detail(app.name())
                .put("version", cleanVersion)
                .put("environment", env.wire())
                .put("fileName", fileName)
                .put("sizeBytes", stored.sizeBytes())
                .put("sha256", stored.sha256()));
        return entry(appId);
    }

    @Transactional
    public CatalogEntry deleteRelease(UUID appId, UUID releaseId, CurrentUser caller) {
        StoreApp app = app(appId);
        StoreRelease release = store.findRelease(releaseId)
                .filter(r -> r.appId().equals(appId))
                .orElseThrow(() -> ApiException.notFound("build"));
        store.deleteRelease(releaseId);
        store.touchApp(appId, caller.id());
        record(caller, AuditAction.STORE_RELEASE_DELETE, appId, detail(app.name())
                .put("version", release.version())
                .put("environment", release.environment().wire()));
        files.delete(release.filePath());
        return entry(appId);
    }

    /**
     * A build to download. 404 — not 403 — for a private app's build unless
     * the caller is an admin, so the route does not confirm that a private
     * app exists.
     */
    @Transactional(readOnly = true)
    public Download download(UUID releaseId, CurrentUser caller) {
        StoreRelease release = store.findRelease(releaseId).orElseThrow(() -> ApiException.notFound("build"));
        StoreApp app = store.findApp(release.appId()).orElseThrow(() -> ApiException.notFound("build"));
        if (!app.isPublic() && !caller.role().isAtLeast(AppRole.ADMIN)) {
            throw ApiException.notFound("build");
        }
        Path path = files.resolve(release.filePath());
        if (!Files.isRegularFile(path)) {
            throw ApiException.notFound("build file");
        }
        return new Download(path, release.fileName(), contentType(release.fileName()), release.sizeBytes());
    }

    /**
     * An app's logo. Served without a session (an {@code <img>} does not
     * reliably carry the cookie across origins — the ADR-101 reasoning), so
     * it answers by app id only and holds nothing but a picture.
     */
    @Transactional(readOnly = true)
    public Download logo(UUID appId) {
        StoreApp app = store.findApp(appId).filter(StoreApp::hasLogo).orElseThrow(() -> ApiException.notFound("logo"));
        Path path = files.resolve(app.logoPath());
        if (!Files.isRegularFile(path)) {
            throw ApiException.notFound("logo");
        }
        return new Download(path, path.getFileName().toString(), app.logoType(), -1);
    }

    /** Android installs an APK it is handed only when it is labelled as one. */
    static String contentType(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".apk")
                ? "application/vnd.android.package-archive"
                : "application/octet-stream";
    }

    private StoreApp app(UUID id) {
        return store.findApp(id).orElseThrow(() -> ApiException.notFound("app"));
    }

    private static String cleanName(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty()) {
            throw ApiException.badRequest("an app needs a name");
        }
        if (clean.length() > MAX_NAME) {
            throw ApiException.badRequest("an app's name must be " + MAX_NAME + " characters or fewer");
        }
        return clean;
    }

    private static String cleanText(String text, String what) {
        return cleanText(text, what, MAX_TEXT);
    }

    private static String cleanText(String text, String what, int max) {
        String clean = text == null ? "" : text.strip();
        if (clean.length() > max) {
            throw ApiException.badRequest(what + " must be " + max + " characters or fewer");
        }
        return clean;
    }

    private ObjectNode detail(String name) {
        return json.createObjectNode().put("name", name);
    }

    private void record(CurrentUser caller, String action, UUID appId, ObjectNode detail) {
        audit.append(caller.id(), caller.email(), action, AuditAction.TARGET_STORE_APP, appId, detail);
    }
}
