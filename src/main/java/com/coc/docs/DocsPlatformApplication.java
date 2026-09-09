package com.coc.docs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Coc Docs Platform backend.
 *
 * <p>Owns all persistence, authorisation and validation. The Next.js frontend is
 * presentation only: it holds no database credentials and enforces no
 * authorisation, so every rule must be enforced here or it is not enforced.
 *
 * <p>// req: CONST-TECH-05, CONST-TECH-06, CONST-TECH-07
 *
 * @see <a href="../../../../../../../docs/design/adr/ADR-012-revision-of-001-backend-stack.md">ADR-012</a>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DocsPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocsPlatformApplication.class, args);
    }
}
