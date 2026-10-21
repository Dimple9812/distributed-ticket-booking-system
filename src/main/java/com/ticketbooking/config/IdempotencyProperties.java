package com.ticketbooking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param retention       how long a completed response is replayable for the same key
 * @param inProgressLease how long an IN_PROGRESS claim blocks other attempts; after that a
 *                        crashed request's claim can be taken over by a retry
 * @param cleanupInterval how often expired records are purged
 */
@ConfigurationProperties(prefix = "app.idempotency")
public record IdempotencyProperties(Duration retention, Duration inProgressLease, Duration cleanupInterval) {
}
