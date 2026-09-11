package com.coc.docs.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Loads {@code .env.local} (repo root, gitignored — see {@code .env.example})
 * as a property source, so {@code DB_HOST}/{@code DB_PORT}/{@code DB_NAME}/
 * {@code DB_USER}/{@code DB_PASSWORD} — referenced as {@code ${...}} in
 * application.yml, with no hard-coded fallback — are available regardless of
 * how the app is launched.
 *
 * <p>Neither Spring Boot, Maven, nor an IDE run configuration loads a
 * {@code .env} file on its own; without this, only a shell that has manually
 * exported the file's contents (or an IDE run config with them typed in)
 * would see these variables, and everyone else gets an unresolved
 * {@code ${DB_HOST}} literal handed straight to the JDBC driver.
 *
 * <p>Added with {@link org.springframework.core.Ordered#LOWEST_PRECEDENCE
 * lowest} priority: a real OS/IDE-configured environment variable of the same
 * name always wins, exactly like every other {@code .env} loader — this only
 * fills in what isn't already set.
 *
 * <p>// req: local DB config via .env.local
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String FILE_NAME = ".env.local";

    /**
     * Searched relative to the JVM's working directory, which differs by
     * launcher: an IDE run configuration for the {@code backend} module
     * typically starts in {@code backend/}, while {@code mvn -f backend/pom.xml
     * spring-boot:run} from the repo root can start in either the repo root
     * or {@code backend/} depending on the environment. The file itself
     * always lives at the repo root, one level above {@code backend/}.
     */
    private static final String[] CANDIDATE_DIRS = {".", "..", "../..", "../../.."};

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path file = findEnvFile();
        if (file == null) {
            return;
        }
        Map<String, Object> values = parse(file);
        if (values.isEmpty()) {
            return;
        }
        environment.getPropertySources().addLast(new MapPropertySource("dotenvLocal", values));
    }

    private static Path findEnvFile() {
        for (String dir : CANDIDATE_DIRS) {
            Path candidate = Path.of(dir, FILE_NAME).normalize();
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** {@code KEY=VALUE} per line; blank lines and {@code #}-comments skipped. */
    private static Map<String, Object> parse(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }

        Map<String, Object> values = new LinkedHashMap<>();
        for (String rawLine : lines) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).strip();
            String value = unquote(line.substring(eq + 1).strip());
            values.put(key, value);
        }
        return values;
    }

    /** Strips one layer of matching quotes, the convention every .env reader uses. */
    private static String unquote(String value) {
        boolean quoted = value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")));
        return quoted ? value.substring(1, value.length() - 1) : value;
    }
}
