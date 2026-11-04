package com.example.shortener.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/// OpenAPI metadata for springdoc. Swagger UI: `/swagger-ui.html`; spec: `/v3/api-docs`.
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    public static final String API_KEY_SCHEME = "apiKey";

    @Bean
    OpenAPI shortenerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("URL Shortener API")
                        .version("v1")
                        .description("Create short links, redirect, and read click analytics. Creating and deleting"
                                + " links and reading stats need an X-API-Key with the matching scope (links:create,"
                                + " links:delete, stats:read); redirects and link lookups are public. Errors are"
                                + " RFC 9457 problem details."))
                .components(new Components()
                        .addSecuritySchemes(
                                API_KEY_SCHEME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name("X-API-Key")));
    }
}
