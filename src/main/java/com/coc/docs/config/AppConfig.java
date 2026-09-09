package com.coc.docs.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Application-wide beans.
 *
 * <p>// req: CONST-TECH-08
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(AppProperties.class)
public class AppConfig {

    /**
     * Injected rather than called statically, so that session expiry, publish
     * timestamps and audit ordering can be driven from a fixed clock in tests.
     * A test that has to sleep for a day to check a TTL is a test nobody runs.
     *
     * <p>// req: NFR-SEC-SESSION
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
