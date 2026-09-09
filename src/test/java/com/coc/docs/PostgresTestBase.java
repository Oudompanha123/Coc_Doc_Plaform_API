package com.coc.docs;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for tests that need a real PostgreSQL instance.
 *
 * <p><b>Why not H2.</b> The schema depends on three things H2 does not
 * reproduce: partial unique indexes (one published row and one draft row per
 * page and locale), a deferred constraint trigger (the last-super-admin guard),
 * and {@code jsonb}. A suite that ran against H2 would pass while leaving
 * exactly those rules unverified — and they are the rules the design leans on
 * hardest, the ones that hold when application code is wrong.
 *
 * <p><b>Why the Docker guard.</b> Docker is not present on every machine, and a
 * suite that cannot run at all locally stops being run. These tests skip with a
 * visible reason rather than failing, so {@code mvnw test} is always useful.
 * The trade is worth stating plainly: a skipped test proves nothing, so a green
 * local build is weaker evidence than a green CI build. CI has Docker, runs
 * these, and is the gate — see {@code hooks/ci-security-auditor.yml}.
 *
 * <p>// req: TEST-PLAN section 3
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
public abstract class PostgresTestBase {

    /**
     * One container for the whole suite.
     *
     * <p>Static, so it starts once rather than per test class. Flyway runs
     * against it on each context start, which is also the migration test: a
     * migration that does not apply cleanly fails every integration test at
     * once, which is the right blast radius for that kind of breakage.
     *
     * <p>The image is pinned. {@code postgres:latest} would mean a test suite
     * whose behaviour changes when someone else publishes an image.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
}
