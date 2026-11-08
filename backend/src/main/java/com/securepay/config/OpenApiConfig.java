package com.securepay.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger UI at /swagger-ui.html (local only). Click "Authorize" and paste a token from POST /api/auth/login. */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI securePayOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SecurePay AI API")
                        .version("1.0")
                        .description("AI agent for sandbox payments, a simulated wallet and read-only AWS. "
                                + "The AI can only prepare drafts; executing money movement requires "
                                + "POST /api/payment-drafts/{ref}/approve by the user."))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
