package com.ticketbooking.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ticketBookingOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Distributed Ticket Booking System API")
                        .version("v1")
                        .description("""
                                Event ticket booking with temporary seat holds, PostgreSQL-enforced seat allocation, \
                                durable idempotency and asynchronous notifications via a Kafka transactional outbox.

                                **Authentication:** obtain a token from `POST /api/v1/auth/login` and send \
                                `Authorization: Bearer <token>`.

                                **Idempotency:** `POST /screenings/{id}/holds` and `POST /bookings/confirm` require an \
                                `Idempotency-Key` header (8-100 chars, `[A-Za-z0-9_-]`). Repeating a request with the same \
                                key and body replays the original response (header `Idempotent-Replayed: true`); the same key \
                                with a different body returns 422; a concurrent duplicate returns 409.
                                """))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}
