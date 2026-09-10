package com.coc.docs.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI contract is generated from these annotated controllers at
 * runtime ({@code /v3/api-docs}), not hand-maintained separately, so the
 * frontend's generated client cannot silently drift from the real API.
 * See {@code docs/api/openapi.yaml} for a committed snapshot and
 * {@code scripts/export-openapi.sh} for regenerating it.
 *
 * // req: architecture-overview.md §11 ("contract first"), RISK-018
 */
@Configuration
public class OpenApiConfig {

    private static final String COOKIE_AUTH = "cookieAuth";

    @Bean
    public OpenAPI cocDocsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Coc Docs Platform API")
                        .description("REST API for documentation content, navigation, media and accounts.")
                        .version("v1"))
                .components(new Components()
                        .addSecuritySchemes(COOKIE_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name("coc_session")
                                .description("Backend-issued session cookie. Obtained from POST /api/auth/login.")))
                .addSecurityItem(new SecurityRequirement().addList(COOKIE_AUTH));
    }
}
