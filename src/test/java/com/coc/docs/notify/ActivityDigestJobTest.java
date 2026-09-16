package com.coc.docs.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * The job's wiring, with no database and no mail server.
 *
 * <p><b>Why this test exists.</b> Every {@code @SpringBootTest} in this project
 * is skipped on a machine without Docker, so on such a machine a green build
 * proves nothing about whether these beans wire, whether the cron placeholder
 * resolves, or whether an unparseable zone is caught. All three fail at
 * startup, which is the one kind of failure a unit test of the service would
 * never see. {@link ApplicationContextRunner} starts a real context containing
 * only these beans, which needs neither Docker nor Postgres.
 *
 * <p>// req: FR-OPS-001
 */
class ActivityDigestJobTest {

    /**
     * The digest's beans and nothing else — scheduling enabled, the properties
     * really bound, the service and clock stood in for.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @EnableConfigurationProperties(NotificationProperties.class)
    static class DigestConfig {

        @Bean
        ActivityDigestService digestService() {
            return mock(ActivityDigestService.class);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        ActivityDigestJob digestJob(ActivityDigestService digest) {
            return new ActivityDigestJob(digest);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(DigestConfig.class);

    private static String cronOf(ScheduledTaskHolder holder) {
        return holder.getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .filter(CronTask.class::isInstance)
                .map(task -> ((CronTask) task).getExpression())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no cron task was registered"));
    }

    @Nested
    @DisplayName("scheduling")
    class Scheduling {

        @Test
        @DisplayName("registers a daily 08:00 cron task by default")
        void registersDefaultCron() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(cronOf(context.getBean(ScheduledTaskHolder.class))).isEqualTo("0 0 8 * * *");
            });
        }

        @Test
        @DisplayName("the cron expression is configurable")
        void honoursConfiguredCron() {
            runner.withPropertyValues("app.notifications.digest-cron=0 30 6 * * MON-FRI")
                    .run(context -> assertThat(cronOf(context.getBean(ScheduledTaskHolder.class)))
                            .isEqualTo("0 30 6 * * MON-FRI"));
        }

        @Test
        @DisplayName("'-' disables the digest without removing the bean")
        void cronCanBeDisabled() {
            runner.withPropertyValues("app.notifications.digest-cron=-").run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(ActivityDigestJob.class);
                assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks())
                        .isEmpty();
            });
        }

        @Test
        @DisplayName("an unparseable cron fails the application at startup, not at 08:00")
        void rejectsBadCron() {
            runner.withPropertyValues("app.notifications.digest-cron=not-a-cron")
                    .run(context -> assertThat(context).hasFailed());
        }

        @Test
        @DisplayName("an unparseable zone fails the application at startup")
        void rejectsBadZone() {
            runner.withPropertyValues("app.notifications.zone=Mars/Olympus")
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Nested
    @DisplayName("properties")
    class Properties {

        @Test
        @DisplayName("bind with working defaults when nothing is configured")
        void bindsDefaults() {
            runner.run(context -> {
                NotificationProperties properties = context.getBean(NotificationProperties.class);
                assertThat(properties.brandName()).isEqualTo("Coc Docs Platform");
                assertThat(properties.zoneId().getId()).isEqualTo("Asia/Phnom_Penh");
                assertThat(properties.recipients()).isEmpty();
                assertThat(properties.sendWhenEmpty()).isFalse();
            });
        }

        @Test
        @DisplayName("bind a configured recipient list")
        void bindsRecipients() {
            runner.withPropertyValues(
                            "app.notifications.recipients[0]=a@example.com",
                            "app.notifications.recipients[1]=b@example.com",
                            "app.notifications.send-when-empty=true",
                            "app.notifications.brand-name=Coc Docs")
                    .run(context -> {
                        NotificationProperties properties = context.getBean(NotificationProperties.class);
                        assertThat(properties.recipients())
                                .containsExactly("a@example.com", "b@example.com");
                        assertThat(properties.sendWhenEmpty()).isTrue();
                        assertThat(properties.brandName()).isEqualTo("Coc Docs");
                    });
        }
    }

    @Test
    @DisplayName("a failing digest never escapes into the scheduler's thread")
    void swallowsFailure() {
        ActivityDigestService service = mock(ActivityDigestService.class);
        when(service.yesterday()).thenReturn(LocalDate.of(2026, 9, 14));
        when(service.sendDigestFor(any(LocalDate.class)))
                .thenThrow(new IllegalStateException("database is down"));

        ActivityDigestJob job = new ActivityDigestJob(service);

        assertThatCode(job::sendYesterdaysDigest).doesNotThrowAnyException();
    }
}
