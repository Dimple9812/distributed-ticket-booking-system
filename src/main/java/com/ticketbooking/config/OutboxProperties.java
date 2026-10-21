package com.ticketbooking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(Duration pollInterval, int batchSize, Duration claimTtl, int maxAttempts,
                               Duration sendTimeout) {
}
