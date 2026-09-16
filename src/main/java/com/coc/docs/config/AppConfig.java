package com.coc.docs.config;

import com.coc.docs.notify.NotificationProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Application-wide beans.
 *
 * <p>// req: CONST-TECH-08
 */
@Configuration
@EnableScheduling
// Push notifications are delivered off the request thread (ADR-025). Without
// this, @Async on RegistrationNotifier is silently ignored and a slow FCM
// holds up the response to somebody who has just registered.
@EnableAsync
@EnableConfigurationProperties({AppProperties.class, NotificationProperties.class})
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

    /**
     * bcrypt, for the password self-registration path (ADR-014).
     *
     * <p>{@code DelegatingPasswordEncoder} would prefix stored hashes with
     * {@code {bcrypt}}; a plain encoder keeps the column readable as what it is
     * and matches what {@code V3__account_registration.sql} documents. Strength
     * is left at the Spring default (10) — raising it is a deployment tuning
     * decision, not something to hard-code differently from everyone else.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
