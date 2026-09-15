package com.coc.docs.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code registration_challenge} rows (ADR-021).
 *
 * <p>// req: ADR-021
 */
@Repository
public class RegistrationChallengeRepository {

    private final JdbcClient jdbc;

    public RegistrationChallengeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static RegistrationChallenge map(ResultSet rs, int rowNum) throws SQLException {
        return new RegistrationChallenge(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("password_hash"),
                rs.getBytes("code_hash"),
                rs.getInt("attempts"),
                rs.getInt("resend_count"),
                rs.getObject("issued_at", OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant());
    }

    /** Case-insensitive, matching {@code AccountRepository.findByEmail}: at most one live row per address. */
    public Optional<RegistrationChallenge> find(String email) {
        return jdbc.sql("""
                        select id, email, display_name, password_hash, code_hash, attempts, resend_count,
                               issued_at, expires_at
                        from registration_challenge
                        where lower(email) = lower(?)
                        """)
                .param(email)
                .query(RegistrationChallengeRepository::map)
                .optional();
    }

    /**
     * Replaces whatever challenge exists for this email — if any — with a
     * fresh one. Used for the first request, an explicit resend, and a
     * resubmission of the form alike: every path that (re)issues a code goes
     * through this one method, so a live row is always exactly one
     * still-current secret, never an extended or top-of-a-stack one.
     *
     * <p>Delete-then-insert rather than an upsert: the two statements run in
     * the caller's transaction ({@code RegistrationOtpService} is {@code
     * @Transactional}), and the unique index on {@code lower(email)} is what
     * actually enforces "at most one" — this is just how that invariant is
     * satisfied on the write path.
     */
    public RegistrationChallenge replace(
            String email, String displayName, String passwordHash, byte[] codeHash,
            Instant expiresAt, int resendCount) {
        jdbc.sql("delete from registration_challenge where lower(email) = lower(?)")
                .param(email)
                .update();

        UUID id = jdbc.sql("""
                        insert into registration_challenge
                            (email, display_name, password_hash, code_hash, expires_at, resend_count)
                        values (?, ?, ?, ?, ?, ?)
                        returning id
                        """)
                .params(
                        email,
                        displayName,
                        passwordHash,
                        codeHash,
                        OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC),
                        resendCount)
                .query(UUID.class)
                .single();

        return find(email)
                .orElseThrow(() -> new IllegalStateException(
                        "registration_challenge row " + id + " vanished immediately after insert"));
    }

    /** A wrong guess. Kept below the 5-attempt cap {@code RegistrationOtpService} enforces. */
    public int incrementAttempts(UUID id) {
        return jdbc.sql("update registration_challenge set attempts = attempts + 1 where id = ?")
                .param(id)
                .update();
    }

    /** Spent, by a successful verify. A second presentation then finds no row at all. */
    public int delete(String email) {
        return jdbc.sql("delete from registration_challenge where lower(email) = lower(?)")
                .param(email)
                .update();
    }

    /**
     * Removes rows whose expiry is past. Table hygiene, not a security
     * control — an expired row is already refused by {@link
     * RegistrationChallenge#isUsable}, which checks the instant directly.
     */
    public int deleteExpiredBefore(Instant cutoff) {
        return jdbc.sql("delete from registration_challenge where expires_at < ?")
                .param(OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .update();
    }
}
