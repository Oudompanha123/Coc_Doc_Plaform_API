package com.coc.docs.notify;

import com.coc.docs.config.SecretBox;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * An account's own SMTP credential (V56, ADR-063).
 *
 * <p>Decryption happens here and nowhere else, so there is exactly one place
 * in the codebase where a stored App Password becomes readable, and it is a
 * place a reviewer can find. Callers get {@link Credential} or nothing.
 *
 * <p>There is deliberately no "find and return the password" method that a
 * controller could reach: {@link #findFor} exists for the mail sender, and the
 * only other read is {@link #exists}, which answers a boolean. ADR-063 §2's
 * "write-only over the wire" property is enforced by this class's shape rather
 * than by remembering not to serialise a field.
 *
 * <p>// req: NFR-SEC-PII, NFR-SEC-CHANNEL, ADR-063
 */
@Repository
public class AccountMailCredentialRepository {

    /**
     * A usable mailbox identity. Exists only inside the sender's call stack.
     *
     * <p>Exactly one of {@code appPassword} and {@code oauthRefreshToken} is
     * set, which the database enforces rather than trusting this record
     * (V62's {@code account_mail_credential_kind_matches_secret}). A row that
     * claims one kind and carries the other cannot be written in the first
     * place, so nothing downstream has to cope with it.
     *
     * @param accountId whose it is — needed to mint and cache an access token
     * @param appPassword set for a {@code app_password} row, null for OAuth
     * @param oauthRefreshToken set for an {@code oauth} row, null otherwise
     */
    public record Credential(
            java.util.UUID accountId, String username, String appPassword, String oauthRefreshToken) {

        /** Whether this authenticates with XOAUTH2 rather than a password. */
        public boolean isOauth() {
            return oauthRefreshToken != null && !oauthRefreshToken.isBlank();
        }
    }

    private final JdbcClient jdbc;
    private final SecretBox secretBox;

    public AccountMailCredentialRepository(JdbcClient jdbc, SecretBox secretBox) {
        this.jdbc = jdbc;
        this.secretBox = secretBox;
    }

    /**
     * The credential to send as, decrypted, or empty when this account has
     * none — which is the normal case and means "use the shared sender".
     *
     * <p>Empty is also the answer when no key is configured. A row that cannot
     * be decrypted is not a reason to fail somebody's attendance mail; falling
     * back to the shared sender delivers it, and the misconfiguration is
     * already reported at startup.
     */
    public Optional<Credential> findFor(UUID accountId) {
        if (!secretBox.isConfigured()) {
            return Optional.empty();
        }
        return jdbc
                .sql(
                        """
                        select smtp_username, app_password_encrypted, oauth_refresh_token_encrypted
                        from account_mail_credential
                        where account_id = ?
                        """)
                .param(accountId)
                .query((rs, rowNum) -> new String[] {
                    rs.getString("smtp_username"),
                    rs.getString("app_password_encrypted"),
                    rs.getString("oauth_refresh_token_encrypted")
                })
                .optional()
                .flatMap(row -> {
                    try {
                        // Decrypt only the one that is set. V62's check
                        // constraint guarantees exactly one is, so this never
                        // has to choose between two.
                        return Optional.of(new Credential(
                                accountId,
                                row[0],
                                row[1] == null ? null : secretBox.decrypt(row[1]),
                                row[2] == null ? null : secretBox.decrypt(row[2])));
                    } catch (RuntimeException e) {
                        // A key rotated without re-encrypting, most likely.
                        // Never log the value, and never fail the send.
                        return Optional.empty();
                    }
                });
    }

    /** Whether one is stored — all the API ever reveals about it. */
    public boolean exists(UUID accountId) {
        return jdbc.sql("select 1 from account_mail_credential where account_id = ?")
                .param(accountId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /**
     * Stores or replaces this account's credential as an App Password.
     *
     * <p>Clears any OAuth grant on the same row: the two kinds are mutually
     * exclusive (V62), so replacing one must remove the other rather than
     * leave a row the check constraint would reject.
     */
    public void save(UUID accountId, String username, String rawAppPassword) {
        jdbc.sql("""
                        insert into account_mail_credential
                          (account_id, smtp_username, kind, app_password_encrypted, updated_at)
                        values (?, ?, 'app_password', ?, now())
                        on conflict (account_id) do update set
                          smtp_username                 = excluded.smtp_username,
                          kind                          = 'app_password',
                          app_password_encrypted        = excluded.app_password_encrypted,
                          oauth_refresh_token_encrypted = null,
                          oauth_scope                   = null,
                          oauth_granted_at              = null,
                          updated_at                    = excluded.updated_at
                        """)
                .params(accountId, username.trim(), secretBox.encrypt(rawAppPassword))
                .update();
    }

    /**
     * Stores or replaces this account's credential as a Google OAuth grant
     * (ADR-085).
     *
     * <p>Symmetrically clears any App Password. Someone moving from one to the
     * other should stop holding the wider secret the moment the narrower one
     * works — leaving it behind would keep a full-mailbox password alive for
     * no reason.
     *
     * @param scope what Google actually granted, which may be less than was
     *     asked for
     */
    public void saveOauth(UUID accountId, String username, String refreshToken, String scope) {
        jdbc.sql("""
                        insert into account_mail_credential
                          (account_id, smtp_username, kind,
                           oauth_refresh_token_encrypted, oauth_scope, oauth_granted_at, updated_at)
                        values (?, ?, 'oauth', ?, ?, now(), now())
                        on conflict (account_id) do update set
                          smtp_username                 = excluded.smtp_username,
                          kind                          = 'oauth',
                          oauth_refresh_token_encrypted = excluded.oauth_refresh_token_encrypted,
                          oauth_scope                   = excluded.oauth_scope,
                          oauth_granted_at              = excluded.oauth_granted_at,
                          app_password_encrypted        = null,
                          updated_at                    = excluded.updated_at
                        """)
                .params(accountId, username.trim(), secretBox.encrypt(refreshToken), scope)
                .update();
    }

    /** Which kind is in force, for the status endpoint. Empty when there is none. */
    public Optional<String> kindOf(UUID accountId) {
        return jdbc.sql("select kind::text from account_mail_credential where account_id = ?")
                .param(accountId)
                .query(String.class)
                .optional();
    }

    /** Removes it. Revoking must be as easy as adding — ADR-063 §4.3. */
    public void delete(UUID accountId) {
        jdbc.sql("delete from account_mail_credential where account_id = ?").param(accountId).update();
    }
}
