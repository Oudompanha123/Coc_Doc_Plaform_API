package com.coc.docs.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates a {@link FirebaseMessaging} only when credentials are configured.
 *
 * <p>The same shape as mail: {@code PushSender} takes this through an
 * {@code ObjectProvider}, so its absence is a supported state rather than a
 * startup failure. Six people run this locally against their own schema, and a
 * component that refused to boot without a Google service account would make
 * the application undevelopable for anyone who had not been handed one.
 *
 * <p>The credential arrives as the service-account JSON itself, in
 * {@code FIREBASE_CREDENTIALS}, rather than as a path to a file. A path means
 * every developer and every deployment needs a file placed at a matching
 * location; an environment variable is the same mechanism as every other
 * secret here (ADR-007), and {@code .env.local} is already gitignored.
 *
 * <p>// req: ADR-007, ADR-025
 */
@Configuration
// Non-empty, not merely present. application.yml declares the key with an
// empty default, and @ConditionalOnProperty would treat "" as a match: this
// class would then try to parse an empty service account and fail startup for
// every developer who has not been given one — the exact opposite of what the
// optional-bean pattern is for.
@ConditionalOnExpression("'${app.firebase.credentials:}' != ''")
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    /** Named so several Spring contexts in one JVM — the test suite — cannot collide. */
    private static final String APP_NAME = "coc-docs-platform";

    @Bean
    FirebaseMessaging firebaseMessaging(
            @org.springframework.beans.factory.annotation.Value("${app.firebase.credentials}")
                    String credentialsJson)
            throws IOException {

        FirebaseApp app = FirebaseApp.getApps().stream()
                .filter(existing -> APP_NAME.equals(existing.getName()))
                .findFirst()
                .orElseGet(() -> {
                    try {
                        FirebaseOptions options = FirebaseOptions.builder()
                                .setCredentials(GoogleCredentials.fromStream(new ByteArrayInputStream(
                                        credentialsJson.getBytes(StandardCharsets.UTF_8))))
                                .build();
                        return FirebaseApp.initializeApp(options, APP_NAME);
                    } catch (IOException e) {
                        // Malformed JSON or a key that is not a service account.
                        // Fatal on purpose: credentials were configured, so
                        // somebody intends push to work, and starting without
                        // it would hide that it does not.
                        throw new IllegalStateException(
                                "app.firebase.credentials is set but is not a readable service account key", e);
                    }
                });

        log.info("Firebase configured; push notifications enabled");
        return FirebaseMessaging.getInstance(app);
    }
}
