package com.coc.docs.web;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AvatarStorage;
import io.swagger.v3.oas.annotations.Operation;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves a profile photo without the bucket being public (ADR-101).
 *
 * <p><b>What this replaces.</b> {@code S3AvatarStorage.upload} returns
 * {@code {endpoint}/{bucket}/{key}}, which a browser can only load if every
 * object is world-readable — and a bucket with public access switched on is
 * not merely readable, it is listable by anyone who finds it. This route keeps
 * the bucket closed and hands out a signed URL valid for fifteen minutes.
 *
 * <p><b>Unauthenticated, and that is a decision rather than an oversight.</b>
 * An {@code <img>} does not reliably send the session cookie: the frontend and
 * this API are different origins, and a cookie that crossed them would need
 * {@code SameSite=None}, which is a worse trade than the one made here. So the
 * protection is the URL itself — the object key carries a random UUID, exactly
 * the unguessability the public-bucket alternative would have relied on.
 *
 * <p>Be clear about what that does and does not buy. Anyone holding the URL
 * can see the photo, as with a public bucket. What is gained is that the
 * bucket is not browsable, an object is never reachable directly, access can
 * be revoked by replacing or deleting the object, and a URL that leaks stops
 * working once the account's photo changes.
 *
 * <p><b>Nothing a client sends becomes an object key.</b> The key is rebuilt
 * from a validated account id and a validated filename, and then checked
 * against what that account actually holds — so this cannot be used to sign a
 * URL for an arbitrary object in the bucket, which is the failure that would
 * turn a photo route into a data leak.
 *
 * <p>// req: FR-AUTH-005, NFR-SEC-PII, ADR-026, ADR-101
 */
@RestController
public class AvatarController {

    /**
     * The shape {@code S3AvatarStorage} writes: a UUID and one of the
     * extensions it allows. Anchored, so nothing with a slash or a {@code ..}
     * in it can reach the key.
     */
    private static final Pattern FILENAME =
            Pattern.compile("^[0-9a-fA-F-]{36}\\.(png|jpg|jpeg|webp)$");

    private final ObjectProvider<AvatarStorage> storage;
    private final AccountRepository accounts;

    public AvatarController(ObjectProvider<AvatarStorage> storage, AccountRepository accounts) {
        this.storage = storage;
        this.accounts = accounts;
    }

    @Operation(
            summary = "Redirect to a short-lived signed URL for a profile photo",
            description = "Public by necessity — an <img> cannot carry the session cookie across "
                    + "origins. The random object key is what makes the URL unguessable.")
    @GetMapping("/api/v1/avatars/{accountId}/{filename}")
    ResponseEntity<Void> avatar(@PathVariable UUID accountId, @PathVariable String filename) {
        AvatarStorage s = storage.getIfAvailable();
        if (s == null || !FILENAME.matcher(filename).matches()) {
            return ResponseEntity.notFound().build();
        }

        String key = "avatars/" + accountId + "/" + filename;

        // The account must actually hold this photo. Without this check the
        // route would sign any key matching the pattern, including one
        // belonging to an account whose photo has since been replaced --
        // an old URL would keep working forever.
        Optional<Account> account = accounts.findById(accountId);
        if (account.isEmpty() || account.get().avatarUrl() == null || !account.get().avatarUrl().endsWith(key)) {
            return ResponseEntity.notFound().build();
        }

        return s.presign(key)
                .map(url -> ResponseEntity.status(302)
                        .location(URI.create(url))
                        // Shorter than the signature's own lifetime, so a
                        // browser never holds a cached redirect to a URL that
                        // has already expired.
                        .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePrivate())
                        .<Void>build())
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
