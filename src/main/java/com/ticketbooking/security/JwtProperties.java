package com.ticketbooking.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * The signing secret has no default: the application refuses to start unless
 * JWT_SECRET is supplied through the environment (HS256 needs >= 256 bits).
 */
@Validated
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(
        @NotBlank @Size(min = 32, message = "JWT secret must be at least 32 characters") String secret,
        @NotBlank String issuer,
        @NotNull Duration accessTokenTtl) {
}
