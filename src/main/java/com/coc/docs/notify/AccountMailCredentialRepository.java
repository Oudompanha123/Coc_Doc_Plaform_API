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

    /** A usable SMTP identity. Exists only inside the sender's call stack. */
    public record Credential(String username, String appPassword) {}

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
                .sql("select smtp_username, app_password_encrypted from account_mail_credential where account_id = ?")
                .param(accountId)
                .query((rs, rowNum) -> new String[] {
                    rs.getString("smtp_username"), rs.getString("app_password_encrypted")
                })
                .optional()
                .flatMap(row -> {
                    try {
                        return Optional.of(new Credential(row[0], secretBox.decrypt(row[1])));
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

    /** Stores or replaces this account's credential. */
    public void save(UUID accountId, String username, String rawAppPassword) {
        jdbc.sql("""
                        insert into account_mail_credential
                          (account_id, smtp_username, app_password_encrypted, updated_at)
                        values (?, ?, ?, now())
                        on conflict (account_id) do update set
                          smtp_username          = excluded.smtp_username,
                          app_password_encrypted = excluded.app_password_encrypted,
                          updated_at             = excluded.updated_at
                        """)
                .params(accountId, username.trim(), secretBox.encrypt(rawAppPassword))
                .update();
    }

    /** Removes it. Revoking must be as easy as adding — ADR-063 §4.3. */
    public void delete(UUID accountId) {
        jdbc.sql("delete from account_mail_credential where account_id = ?").param(accountId).update();
    }
}
