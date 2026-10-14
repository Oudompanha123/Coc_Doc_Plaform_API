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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Store page (ADR-108, ADR-111): apps, their builds, and the files
 * behind them. An app is a name, logo, description and visibility; each
 * build is one file for one platform, version and environment, and the
 * platform comes from the file's type.
 *
 * <p><b>Two audiences.</b> {@link #publicCatalog} is what every signed-in
 * user sees — every public app. {@link #fullCatalog} and the write methods
 * are for admins; {@code SecurityConfig} decides who reaches them, by path,
 * per ADR-011. The places this class looks at the caller's role itself are
 * {@link #entryForReader} and {@link #download}: those routes are open to
 * every user, and only an admin may reach a private app through them.
 *
 * <p><b>Database and disk are kept in step by order, not by a shared
 * transaction.</b> A file is written before its row is inserted and removed
 * after its row is marked removed, so a failure in between leaves at worst
 * an unreferenced file — never a row pointing at nothing, which would be a
 * broken download button.
 *
 * <p>// req: ADR-108, ADR-111, ADR-112, NFR-OPS-AUDIT
 */
@Service
public class StoreService {

    static final int MAX_NAME = 80;
    static final int MAX_TEXT = 2000;

    /** The "What's new" box of the Next version form. */
    static final int MAX_NOTES = 1500;

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

    /** An app with all its builds, removed ones included, newest first. */
    public record CatalogEntry(StoreApp app, List<StoreBuild> builds) {}

    /** A file to send: where it is on disk, what to call it, and its content type. */
    public record Download(Path path, String fileName, String contentType) {}

    /** Every public app — what a signed-in user sees. */
    @Transactional(readOnly = true)
    public List<CatalogEntry> publicCatalog() {
        return catalog(store.findApps(true));
    }

    /** Every app, private ones included — the admin list. */
    @Transactional(readOnly = true)
    public List<CatalogEntry> fullCatalog() {
        return catalog(store.findApps(false));
    }

    /**
     * One app's page ({@code /store-<number>}). A user gets a public app and a
     * 404 for anything else, so a private app's number cannot be confirmed by
     * trying it. An admin sees every app.
     */
    @Transactional(readOnly = true)
    public CatalogEntry entryForReader(long number, CurrentUser caller) {
        StoreApp app = store.findAppByNumber(number).orElseThrow(() -> ApiException.notFound("app"));
        if (!app.isPublic() && !caller.role().isAtLeast(AppRole.ADMIN)) {
            throw ApiException.notFound("app");
        }
        return new CatalogEntry(app, store.findBuilds(List.of(app.id())));
    }

    private List<CatalogEntry> catalog(List<StoreApp> apps) {
        Map<UUID, List<StoreBuild>> byApp = store.findBuilds(apps.stream().map(StoreApp::id).toList()).stream()
                .collect(Collectors.groupingBy(StoreBuild::appId));
        return apps.stream().map(a -> new CatalogEntry(a, byApp.getOrDefault(a.id(), List.of()))).toList();
    }

    private CatalogEntry entry(UUID appId) {
        return new CatalogEntry(app(appId), store.findBuilds(List.of(appId)));
    }

    // --- apps -------------------------------------------------------------------

    @Transactional
    public CatalogEntry createApp(
            String name, String description, String category, boolean isPublic, CurrentUser caller) {
        StoreCategory cleanCategory = cleanCategory(category);
        String cleanName = cleanName(name);
        String cleanDescription = cleanText(description, "the description", MAX_TEXT);
        refuseDuplicateName(cleanName, null);
        UUID id = store.insertApp(cleanName, cleanDescription, cleanCategory, isPublic, caller.id());
        record(caller, AuditAction.STORE_APP_CREATE, id,
                detail(cleanName).put("category", cleanCategory.wire()).put("public", isPublic));
        return entry(id);
    }

    @Transactional
    public CatalogEntry updateApp(
            UUID id, String name, String description, String category, boolean isPublic, CurrentUser caller) {
        StoreApp current = app(id);
        StoreCategory cleanCategory = cleanCategory(category);
        if (cleanCategory != current.category()
                && store.findBuilds(List.of(id)).stream().anyMatch(StoreBuild::isLive)) {
            throw ApiException.conflict("the app has builds, so it cannot change from "
                    + current.category().label() + " to " + cleanCategory.label() + " — remove them first");
        }
        String cleanName = cleanName(name);
        String cleanDescription = cleanText(description, "the description", MAX_TEXT);
        refuseDuplicateName(cleanName, id);
        store.updateApp(id, cleanName, cleanDescription, cleanCategory, isPublic, caller.id());
        ObjectNode detail = detail(cleanName).put("category", cleanCategory.wire()).put("public", isPublic);
        if (current.isPublic() != isPublic) {
            detail.put("visibilityChanged", true);
        }
        record(caller, AuditAction.STORE_APP_UPDATE, id, detail);
        return entry(id);
    }

    /**
     * One app per name across the Store (an app spans platforms, ADR-111). A
     * new version is a build uploaded to the existing app, never a second app.
     * Refused with 409 in the service rather than as a database constraint,
     * so a database already holding such a pair still migrates.
     */
    private void refuseDuplicateName(String name, UUID excludeId) {
        if (store.appNameTaken(name, excludeId)) {
            throw ApiException.conflict("an app named \"" + name
                    + "\" already exists — upload the next version on its page instead of adding it again");
        }
    }

    /** Removes the app, every build of it, and every file behind them. */
    @Transactional
    public void deleteApp(UUID id, CurrentUser caller) {
        StoreApp app = app(id);
        List<StoreBuild> builds = store.findBuilds(List.of(id));
        store.deleteApp(id);
        record(caller, AuditAction.STORE_APP_DELETE, id, detail(app.name()).put("builds", builds.size()));
        // After the rows: a crash here leaves stray files, not dead buttons.
        builds.stream().filter(StoreBuild::isLive).forEach(b -> files.delete(b.filePath()));
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

    // --- builds -----------------------------------------------------------------

    /**
     * Uploads the next version of an app: one file, published to one or both
     * environments. The platform is the file's type (.apk Android, .ipa iOS,
     * ...). A version that already has a live build of that platform in a
     * chosen environment is refused (409) before anything is written.
     *
     * <p>Not {@code @Transactional}: the upload can take minutes, and a
     * database connection should not be held open — or a row locked — for the
     * length of somebody's network.
     */
    public CatalogEntry uploadBuild(
            UUID appId,
            String version,
            List<String> environments,
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
        Set<StoreEnvironment> envs = new LinkedHashSet<>();
        for (String environment : environments == null ? List.<String>of() : environments) {
            envs.add(StoreEnvironment.parse(environment)
                    .orElseThrow(() -> ApiException.badRequest("the server type must be production or development")));
        }
        if (envs.isEmpty()) {
            throw ApiException.badRequest("choose Production, Development, or both");
        }
        String cleanNotes = cleanText(notes, "what's new", MAX_NOTES);
        String fileName = StoreFileStorage.safeFileName(originalFileName);
        StorePlatform platform = StorePlatform.ofFile(fileName)
                .orElseThrow(() -> ApiException.badRequest("the file must be an app installer: " + StorePlatform.allExtensions()));
        if (!app.category().includes(platform)) {
            throw ApiException.badRequest("this is a " + app.category().label() + " app, so the file must be one of: "
                    + app.category().extensions());
        }
        for (StoreEnvironment env : envs) {
            if (store.liveBuildExists(appId, platform, cleanVersion, env)) {
                throw ApiException.conflict("this app already has a " + platform.wire() + " " + env.wire()
                        + " build of version " + cleanVersion + " — remove it first to replace it");
            }
        }

        StoreFileStorage.StoredBuild stored = files.storeBuild(
                platform, cleanVersion, List.copyOf(envs), appId, fileName, body,
                env -> !store.liveBuildExists(appId, platform, cleanVersion, env));
        List<UUID> inserted = new ArrayList<>();
        try {
            for (StoreEnvironment env : envs) {
                inserted.add(store.insertBuild(appId, platform, cleanVersion, env, cleanNotes, fileName,
                        stored.paths().get(env), stored.sizeBytes(), stored.sha256(), caller.id()));
            }
        } catch (DuplicateKeyException e) {
            // Another upload of the same version won the race. Take back what
            // this one wrote — rows first, then the files only this one owns.
            inserted.forEach(id -> store.markBuildRemoved(id, caller.id()));
            stored.paths().values().forEach(files::delete);
            throw ApiException.conflict("this app already has a " + platform.wire() + " build of version " + cleanVersion);
        }
        store.touchApp(appId, caller.id());
        record(caller, AuditAction.STORE_BUILD_UPLOAD, appId, detail(app.name())
                .put("platform", platform.wire())
                .put("version", cleanVersion)
                .put("environments", envs.stream().map(StoreEnvironment::wire).collect(Collectors.joining(",")))
                .put("fileName", fileName)
                .put("sizeBytes", stored.sizeBytes())
                .put("sha256", stored.sha256()));
        return entry(appId);
    }

    /**
     * Removes one build: its file is deleted, its row is kept and marked
     * removed, so the history shows "Removed". Every other build is untouched.
     * 409 if it was already removed.
     */
    @Transactional
    public CatalogEntry removeBuild(UUID appId, UUID buildId, CurrentUser caller) {
        StoreApp app = app(appId);
        StoreBuild build = store.findBuild(buildId)
                .filter(b -> b.appId().equals(appId))
                .orElseThrow(() -> ApiException.notFound("build"));
        if (!store.markBuildRemoved(buildId, caller.id())) {
            throw ApiException.conflict("this build has already been removed");
        }
        store.touchApp(appId, caller.id());
        record(caller, AuditAction.STORE_BUILD_REMOVE, appId, detail(app.name())
                .put("platform", build.platform().wire())
                .put("version", build.version())
                .put("environment", build.environment().wire()));
        files.delete(build.filePath());
        return entry(appId);
    }

    /**
     * A build to download. 404 — not 403 — for a removed build, and for a
     * private app's build unless the caller is an admin, so the route does
     * not confirm that a private app exists.
     */
    @Transactional(readOnly = true)
    public Download download(UUID buildId, CurrentUser caller) {
        StoreBuild build = store.findBuild(buildId).filter(StoreBuild::isLive)
                .orElseThrow(() -> ApiException.notFound("build"));
        StoreApp app = store.findApp(build.appId()).orElseThrow(() -> ApiException.notFound("build"));
        if (!app.isPublic() && !caller.role().isAtLeast(AppRole.ADMIN)) {
            throw ApiException.notFound("build");
        }
        Path path = files.resolve(build.filePath());
        if (!Files.isRegularFile(path)) {
            throw ApiException.notFound("build file");
        }
        return new Download(path, build.fileName(), contentType(build.fileName()));
    }

    /** Android installs an APK it is handed only when it is labelled as one. */
    static String contentType(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".apk")
                ? "application/vnd.android.package-archive"
                : "application/octet-stream";
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
        return new Download(path, path.getFileName().toString(), app.logoType());
    }

    private StoreApp app(UUID id) {
        return store.findApp(id).orElseThrow(() -> ApiException.notFound("app"));
    }

    private static StoreCategory cleanCategory(String category) {
        return StoreCategory.parse(category)
                .orElseThrow(() -> ApiException.badRequest("choose Mobile or PC as the app's platform"));
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
