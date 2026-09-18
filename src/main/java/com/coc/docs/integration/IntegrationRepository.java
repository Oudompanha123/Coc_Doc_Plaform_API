package com.coc.docs.integration;

import com.coc.docs.web.Jsonb;
import com.fasterxml.jackson.core.type.TypeReference;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads and writes {@code integration} rows (V23, ADR-037). */
@Repository
public class IntegrationRepository {

    private static final String COLUMNS = """
            id, provider, name, target_url, signing_secret_iv, signing_secret_ciphertext,
            encryption_key_version, events::text as events, status, last_tested_at, last_test_status,
            created_by, created_at, updated_at
            """;

    private final JdbcClient jdbc;
    private final Jsonb jsonb;

    public IntegrationRepository(JdbcClient jdbc, Jsonb jsonb) {
        this.jdbc = jdbc;
        this.jsonb = jsonb;
    }

    private Integration map(ResultSet rs, int rowNum) throws SQLException {
        List<String> events = jsonb.mapper().convertValue(jsonb.read(rs.getString("events")), new TypeReference<>() {});
        return new Integration(
                rs.getObject("id", UUID.class),
                rs.getString("provider"),
                rs.getString("name"),
                rs.getString("target_url"),
                rs.getBytes("signing_secret_iv"),
                rs.getBytes("signing_secret_ciphertext"),
                rs.getShort("encryption_key_version"),
                events,
                rs.getString("status"),
                toInstant(rs.getObject("last_tested_at", OffsetDateTime.class)),
                rs.getString("last_test_status"),
                rs.getObject("created_by", UUID.class),
                toInstant(rs.getObject("created_at", OffsetDateTime.class)),
                toInstant(rs.getObject("updated_at", OffsetDateTime.class)));
    }

    private static java.time.Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public List<Integration> list() {
        return jdbc.sql("select " + COLUMNS + " from integration order by created_at desc")
                .query(this::map)
                .list();
    }

    public Optional<Integration> findById(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from integration where id = ?")
                .param(id)
                .query(this::map)
                .optional();
    }

    /** Every connected integration subscribed to {@code eventType} — the dispatcher's one query. */
    public List<Integration> findConnectedSubscribedTo(String eventType) {
        return jdbc.sql("""
                        select """ + COLUMNS + """

                        from integration
                        where status = 'connected'
                          and events @> ?::jsonb
                        """)
                .param(jsonb.write(jsonb.mapper().createArrayNode().add(eventType)))
                .query(this::map)
                .list();
    }

    public Integration insert(
            String provider,
            String name,
            String targetUrl,
            byte[] signingSecretIv,
            byte[] signingSecretCiphertext,
            short encryptionKeyVersion,
            List<String> events,
            UUID createdBy) {

        return jdbc.sql("""
                        insert into integration
                          (provider, name, target_url, signing_secret_iv, signing_secret_ciphertext,
                           encryption_key_version, events, created_by)
                        values (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                        returning
                        """ + COLUMNS)
                .params(
                        provider,
                        name,
                        targetUrl,
                        signingSecretIv,
                        signingSecretCiphertext,
                        encryptionKeyVersion,
                        jsonb.write(jsonb.mapper().valueToTree(events)),
                        createdBy)
                .query(this::map)
                .single();
    }

    public void updateNameAndEvents(UUID id, String name, List<String> events) {
        jdbc.sql("update integration set name = ?, events = ?::jsonb, updated_at = now() where id = ?")
                .params(name, jsonb.write(jsonb.mapper().valueToTree(events)), id)
                .update();
    }

    public void updateStatus(UUID id, String status) {
        jdbc.sql("update integration set status = ?, updated_at = now() where id = ?")
                .params(status, id)
                .update();
    }

    public void updateSigningSecret(UUID id, byte[] signingSecretIv, byte[] signingSecretCiphertext, short encryptionKeyVersion) {
        jdbc.sql("""
                        update integration
                        set signing_secret_iv = ?, signing_secret_ciphertext = ?,
                            encryption_key_version = ?, updated_at = now()
                        where id = ?
                        """)
                .params(signingSecretIv, signingSecretCiphertext, encryptionKeyVersion, id)
                .update();
    }

    /** Disconnect: status + nulled secret columns in one statement — see {@code Integration}'s Javadoc. */
    public void disconnect(UUID id) {
        jdbc.sql("""
                        update integration
                        set status = ?, signing_secret_iv = null, signing_secret_ciphertext = null, updated_at = now()
                        where id = ?
                        """)
                .params(Integration.STATUS_DISABLED, id)
                .update();
    }

    public void recordTestResult(UUID id, String testStatus) {
        jdbc.sql("update integration set last_tested_at = now(), last_test_status = ? where id = ?")
                .params(testStatus, id)
                .update();
    }
}
