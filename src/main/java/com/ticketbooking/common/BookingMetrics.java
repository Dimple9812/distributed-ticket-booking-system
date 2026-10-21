package com.ticketbooking.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Business metrics exposed at /actuator/prometheus. Tag values are a small fixed
 * set (never ids or user input) to keep metric cardinality bounded.
 */
@Component
public class BookingMetrics {

    private final MeterRegistry registry;

    public BookingMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** outcome: success | conflict | invalid | error */
    public void holdAttempt(String outcome) {
        Counter.builder("booking.holds.attempts").description("Seat hold attempts by outcome")
                .tag("outcome", outcome).register(registry).increment();
    }

    /** source: redis | database - where the conflicting hold was detected */
    public void seatConflict(String source) {
        Counter.builder("booking.seat.conflicts").description("Seat hold conflicts by detection layer")
                .tag("source", source).register(registry).increment();
    }

    public void redisDegraded(String operation) {
        Counter.builder("booking.redis.degraded").description("Operations that proceeded/failed because Redis was unavailable")
                .tag("operation", operation).register(registry).increment();
    }

    /** outcome: success | expired | conflict | payment_declined | error */
    public void confirmation(String outcome) {
        Counter.builder("booking.confirmations").description("Booking confirmation attempts by outcome")
                .tag("outcome", outcome).register(registry).increment();
    }

    public void cancellation() {
        Counter.builder("booking.cancellations").description("Cancelled bookings").register(registry).increment();
    }

    public void holdsReleased(String reason, int count) {
        Counter.builder("booking.holds.released").description("Holds that ended without a booking")
                .tag("reason", reason).register(registry).increment(count);
    }

    public void idempotentReplay(String operation) {
        Counter.builder("booking.idempotency.replays").description("Requests answered from a stored idempotent response")
                .tag("operation", operation).register(registry).increment();
    }

    /** reason: validation | processing | dead_lettered */
    public void consumerError(String reason) {
        Counter.builder("booking.kafka.consumer.errors").description("Kafka consumer failures")
                .tag("reason", reason).register(registry).increment();
    }

    public void outboxPublished(String outcome) {
        Counter.builder("booking.outbox.publish").description("Outbox publish attempts by outcome")
                .tag("outcome", outcome).register(registry).increment();
    }
}
