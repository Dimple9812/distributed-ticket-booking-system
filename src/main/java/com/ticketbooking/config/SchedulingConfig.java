package com.ticketbooking.config;

import com.ticketbooking.booking.HoldExpiryService;
import com.ticketbooking.idempotency.IdempotencyService;
import com.ticketbooking.outbox.OutboxPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Background workers. Disabled in tests (app.scheduling.enabled=false), where the
 * workers are invoked directly so that every step is deterministic.
 * All jobs are safe to run on several instances at once (see each service).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {

    private static final Logger log = LoggerFactory.getLogger(SchedulingConfig.class);

    private final HoldExpiryService holdExpiryService;
    private final OutboxPublisher outboxPublisher;
    private final IdempotencyService idempotencyService;

    public SchedulingConfig(HoldExpiryService holdExpiryService, OutboxPublisher outboxPublisher,
                            IdempotencyService idempotencyService) {
        this.holdExpiryService = holdExpiryService;
        this.outboxPublisher = outboxPublisher;
        this.idempotencyService = idempotencyService;
    }

    @Scheduled(fixedDelayString = "${app.booking.expiry.interval}", initialDelayString = "PT5S")
    public void expireHolds() {
        holdExpiryService.expireDueHolds();
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval}", initialDelayString = "PT2S")
    public void publishOutbox() {
        outboxPublisher.publishPending();
    }

    @Scheduled(fixedDelayString = "${app.idempotency.cleanup-interval}", initialDelayString = "PT1M")
    public void purgeIdempotencyRecords() {
        int purged = idempotencyService.purgeExpired();
        if (purged > 0) {
            log.info("Purged {} expired idempotency record(s)", purged);
        }
    }
}
