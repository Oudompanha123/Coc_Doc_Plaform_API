package com.coc.docs.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The team shares one PostgreSQL server and each developer owns one schema
 * (CONTRIBUTING.md, "One server, one schema each"). This pins the two places
 * that decide which schema a developer's application touches.
 *
 * <p>It exists because the guarantee was silently lost once. {@code 4c9642b}
 * made the connection URL read {@code currentSchema=${DB_SCHEMA}}; {@code
 * 555a769} put the literal {@code cocdocs_dev} back, almost certainly while
 * resolving a merge. Nothing failed. Every developer's application quietly
 * connected to one person's schema whatever their {@code DB_SCHEMA} said, and
 * an unpushed {@code V8__temp_password.sql} was applied to it by someone who
 * never intended to go near it — discovered only when the owner's application
 * refused to start against a migration that exists on no branch.
 *
 * <p>A comment would not have caught that, and neither would review: the
 * change looked like one line of a merge resolution. A test does, at the
 * moment it is introduced rather than days later in someone else's database.
 *
 * <p>// req: CONST-OPS-03
 */
class SchemaIsPerDeveloperTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> applicationYml() {
        try (InputStream in =
                SchemaIsPerDeveloperTest.class.getResourceAsStream("/application.yml")) {
            return new Yaml().loadAll(in).iterator().next() instanceof Map<?, ?> m
                    ? (Map<String, Object>) m
                    : Map.of();
        } catch (Exception e) {
            throw new IllegalStateException("could not read application.yml", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static String at(String... path) {
        Object node = applicationYml();
        for (String key : path) {
            node = ((Map<String, Object>) node).get(key);
        }
        return String.valueOf(node);
    }

    @Test
    @DisplayName("the connection names the developer's own schema, never a literal one")
    void connectionUrlUsesTheEnvironmentSchema() {
        String url = at("spring", "datasource", "url");

        assertThat(url)
                .as("the schema a developer connects to must come from DB_SCHEMA")
                .contains("currentSchema=${DB_SCHEMA}");
        // The failure that started this: a real schema name compiled into the
        // URL, which no amount of setting DB_SCHEMA can override.
        assertThat(url)
                .as("no developer's schema may be hard-coded into the connection URL")
                .doesNotContain("cocdocs_");
    }

    @Test
    @DisplayName("Flyway migrates the same schema the connection uses")
    void flywayMigratesTheConnectedSchema() {
        // These two drifting apart is worse than either being wrong alone: the
        // tables land in one schema while every query reads another, and the
        // first symptom is a bad-SQL-grammar error nowhere near the cause.
        assertThat(at("spring", "flyway", "schemas")).isEqualTo("${DB_SCHEMA}");
    }

    @Test
    @DisplayName("neither setting carries a default, so an unset DB_SCHEMA stops the app")
    void thereIsNoFallbackSchema() {
        // `${DB_SCHEMA:something}` is the shape that caused this. A default
        // here is not a convenience — it is a name that belongs to somebody,
        // and it turns "I forgot to configure this" into "I am writing to a
        // colleague's database" with no error in between.
        assertThat(at("spring", "datasource", "url"))
                .as("a default schema silently sends the unconfigured to somebody else's data")
                .doesNotContain("${DB_SCHEMA:");
        assertThat(at("spring", "flyway", "schemas")).doesNotContain("${DB_SCHEMA:");
    }
}
