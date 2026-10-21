package com.ticketbooking.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.booking")
public class BookingProperties {

    @NotBlank
    @Pattern(regexp = "[A-Z]{3}")
    private String currency = "INR";

    @Min(1)
    @Max(50)
    private int maxSeatsPerHold = 10;

    @NotNull
    private Duration holdTtl = Duration.ofMinutes(5);

    @NotNull
    private Duration cancellationCutoff = Duration.ofHours(2);

    private final RedisLock redisLock = new RedisLock();
    private final Expiry expiry = new Expiry();

    public enum RedisFailureMode {
        /** Redis unavailable: proceed with PostgreSQL-only protection (still correct, more DB contention). */
        DB_ONLY,
        /** Redis unavailable: reject new holds with 503. */
        FAIL_CLOSED
    }

    public static class RedisLock {
        private boolean enabled = true;
        private RedisFailureMode failureMode = RedisFailureMode.DB_ONLY;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public RedisFailureMode getFailureMode() { return failureMode; }
        public void setFailureMode(RedisFailureMode failureMode) { this.failureMode = failureMode; }
    }

    public static class Expiry {
        private Duration interval = Duration.ofSeconds(10);
        private int batchSize = 100;

        public Duration getInterval() { return interval; }
        public void setInterval(Duration interval) { this.interval = interval; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public int getMaxSeatsPerHold() { return maxSeatsPerHold; }
    public void setMaxSeatsPerHold(int maxSeatsPerHold) { this.maxSeatsPerHold = maxSeatsPerHold; }
    public Duration getHoldTtl() { return holdTtl; }
    public void setHoldTtl(Duration holdTtl) { this.holdTtl = holdTtl; }
    public Duration getCancellationCutoff() { return cancellationCutoff; }
    public void setCancellationCutoff(Duration cancellationCutoff) { this.cancellationCutoff = cancellationCutoff; }
    public RedisLock getRedisLock() { return redisLock; }
    public Expiry getExpiry() { return expiry; }
}
