package com.coc.docs.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeps three copies of the permission catalogue honest with each other: the
 * migration that seeds it, the {@link Permission} enum the code checks against,
 * and {@link TestPermissions}, the mirror unit tests use.
 *
 * <p>Reads the migration file rather than a database, so it runs everywhere —
 * including on a machine with no Docker, which is exactly where someone is
 * most likely to add a permission and not notice the other two copies.
 *
 * <p>The failure this prevents is quiet. A permission seeded but missing from
 * the enum resolves to nothing and the caller is refused; a constant with no
 * seeded row is never granted to anyone. Both read, from the outside, as the
 * system working correctly, so nobody reports them.
 *
 * <p>// req: FR-AUTH-005, ADR-016
 */
class PermissionCatalogTest {

    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration/V4__roles_and_permissions.sql");

    private static String sql() {
        try {
            return Files.readString(MIGRATION, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + MIGRATION.toAbsolutePath(), e);
        }
    }

    /** The {@code insert into permission ... values ('key', 'description'), ...} block. */
    private static Set<String> seededPermissionKeys() {
        String block = block("insert into permission (key, description) values");
        Set<String> keys = new TreeSet<>();
        Matcher m = Pattern.compile("\\('([^']+)'\\s*,").matcher(block);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }

    /** The {@code insert into role_permission ... values ('role', 'permission'), ...} block. */
    private static TreeMap<String, Set<String>> seededGrants() {
        String block = block("insert into role_permission (role_key, permission_key) values");
        TreeMap<String, Set<String>> grants = new TreeMap<>();
        Matcher m = Pattern.compile("\\('([^']+)'\\s*,\\s*'([^']+)'\\)").matcher(block);
        while (m.find()) {
            grants.computeIfAbsent(m.group(1), k -> new LinkedHashSet<>()).add(m.group(2));
        }
        return grants;
    }

    /** From the marker to the statement's terminating semicolon. */
    private static String block(String marker) {
        String sql = sql();
        int start = sql.indexOf(marker);
        assertThat(start).as("migration should contain: %s", marker).isNotNegative();
        int end = sql.indexOf(';', start);
        assertThat(end).as("statement after '%s' should be terminated", marker).isNotNegative();
        return sql.substring(start, end);
    }

    @Test
    @DisplayName("every seeded permission has a Permission constant, and vice versa")
    void enumMatchesTheSeed() {
        Set<String> seeded = seededPermissionKeys();
        Set<String> declared = new TreeSet<>();
        for (Permission p : Permission.values()) {
            declared.add(p.key());
        }
        assertThat(declared).isEqualTo(seeded);
    }

    @Test
    @DisplayName("TestPermissions mirrors what the migration grants each role")
    void mirrorMatchesTheSeed() {
        TreeMap<String, Set<String>> seeded = seededGrants();

        for (AppRole role : AppRole.values()) {
            Set<String> fromMirror = new TreeSet<>();
            TestPermissions.of(role).forEach(p -> fromMirror.add(p.key()));

            Set<String> fromSeed = new TreeSet<>(seeded.getOrDefault(role.wire(), Set.of()));

            assertThat(fromMirror).as("permissions granted to %s", role.wire()).isEqualTo(fromSeed);
        }
    }

    @Test
    @DisplayName("the ladder still holds: a higher role grants everything a lower one does")
    void higherRolesAreSupersets() {
        // Not required by the schema — role_permission could grant anything to
        // anyone — but it is what page.min_role's rank comparison assumes. If
        // this ever stops being true, min_role stops meaning what it says.
        AppRole[] ladder = AppRole.values();
        for (int i = 1; i < ladder.length; i++) {
            Set<Permission> lower = EnumSet.copyOf(nonEmpty(TestPermissions.of(ladder[i - 1])));
            Set<Permission> higher = EnumSet.copyOf(nonEmpty(TestPermissions.of(ladder[i])));
            assertThat(higher)
                    .as("%s should grant everything %s does", ladder[i].wire(), ladder[i - 1].wire())
                    .containsAll(lower);
        }
    }

    /** EnumSet.copyOf refuses an empty collection it cannot infer a type from. */
    private static Set<Permission> nonEmpty(Set<Permission> set) {
        return set.isEmpty() ? EnumSet.noneOf(Permission.class) : set;
    }
}
