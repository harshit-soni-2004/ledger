package dev.ihars.ledger.shared.web;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/** OpenAPI metadata. The document itself is served only in the local profile. */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
        info = @Info(title = "Ledger API", version = "v1",
                description = "Double-entry ledger. Authenticate with Authorization: Bearer <API key>."),
        security = @SecurityRequirement(name = OpenApiConfiguration.API_KEY))
@SecurityScheme(name = OpenApiConfiguration.API_KEY, type = SecuritySchemeType.HTTP, scheme = "bearer",
        description = "API key issued by an ADMIN (lk_...). Sent as a bearer token.")
public class OpenApiConfiguration {

    static final String API_KEY = "apiKey";
}
