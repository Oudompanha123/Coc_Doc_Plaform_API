package com.coc.docs.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.coc.docs.notify.HrmsMailProperties;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;

/**
 * {@code backend/.env.local} is written in environment-variable spelling, and
 * this pins that Spring reads it that way.
 *
 * <p>It exists because the guarantee was never actually there. The loader
 * installed the file as a plain {@code MapPropertySource}, which Spring
 * matches literally — so {@code ${DB_HOST}} in {@code application.yml} worked
 * (an exact name), and every setting bound by prefix did not.
 * {@code APP_HRMS_ENABLED=true} in the file bound nothing, and the feature
 * reported itself off at startup: correct file, loaded, ignored. The same held
 * for {@code APP_MAIL_FROM} and {@code APP_NOTIFICATIONS_RECIPIENTS}, which
 * {@code backend/.env.example} documents as reaching their properties "through
 * Spring Boot's relaxed environment binding" — true of a real environment
 * variable, and not of this file until the source type and name made it so.
 *
 * <p>Both halves are load-bearing and both are easy to undo while tidying:
 * the source must be a {@code SystemEnvironmentPropertySource}, and its name
 * must end in {@code -systemEnvironment}, or Spring applies literal matching
 * again. Neither is visible at the call site. A failing startup would have
 * caught this; a feature that degrades quietly to "off" never does.
 *
 * <p>// req: local DB config via backend/.env.local
 */
class DotenvEnvironmentPostProcessorTest {

    private static Binder binderOver(Map<String, Object> dotenv) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(DotenvEnvironmentPostProcessor.propertySource(dotenv));
        return Binder.get(environment);
    }

    @Test
    @DisplayName("UPPER_SNAKE keys bind to a @ConfigurationProperties prefix")
    void upperSnakeKeysBindByPrefix() {
        HrmsMailProperties bound = binderOver(Map.of(
                        "APP_HRMS_ENABLED", "true",
                        "APP_HRMS_USERNAME", "someone@gmail.com",
                        "APP_HRMS_PASSWORD", "app-password",
                        "APP_HRMS_SENDER", "noreply-hrms@wecambodia.com",
                        "APP_HRMS_LOOKBACK", "P30D"))
                .bind("app.hrms", Bindable.of(HrmsMailProperties.class))
                .orElseThrow(() -> new AssertionError("app.hrms bound nothing"));

        // usable() is the exact thing that was false in production: enabled,
        // and complete enough to open a connection with.
        assertThat(bound.usable()).isTrue();
        assertThat(bound.sender()).isEqualTo("noreply-hrms@wecambodia.com");
        assertThat(bound.lookback().toDays()).isEqualTo(30);
        // Defaults still come from the record, not from the file.
        assertThat(bound.host()).isEqualTo("imap.gmail.com");
        assertThat(bound.port()).isEqualTo(993);
        assertThat(bound.folder()).isEqualTo("INBOX");
    }

    @Test
    @DisplayName("UPPER_SNAKE keys resolve as a dotted ${placeholder} too")
    void upperSnakeKeysResolveAsDottedPlaceholders() {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addLast(DotenvEnvironmentPostProcessor.propertySource(Map.of("APP_HRMS_POLL_CRON", "0 * * * * *")));

        // The @Scheduled cron on HrmsMailPollJob is a placeholder, not a bound
        // property — a different lookup path, and it has to work as well.
        assertThat(environment.resolvePlaceholders(HrmsMailProperties.POLL_CRON)).isEqualTo("0 * * * * *");
    }

    @Test
    @DisplayName("exact-name lookups, the shape application.yml already uses, still work")
    void exactNamesStillResolve() {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addLast(DotenvEnvironmentPostProcessor.propertySource(Map.of("DB_HOST", "db.example.internal")));

        assertThat(environment.getProperty("DB_HOST")).isEqualTo("db.example.internal");
    }
}
