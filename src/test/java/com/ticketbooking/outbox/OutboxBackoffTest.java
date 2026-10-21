package com.ticketbooking.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxBackoffTest {

    @Test
    void backoffGrowsExponentiallyAndIsCapped() {
        assertThat(OutboxPublisher.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(OutboxPublisher.backoff(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(OutboxPublisher.backoff(5)).isEqualTo(Duration.ofSeconds(16));
        assertThat(OutboxPublisher.backoff(30)).isEqualTo(Duration.ofMinutes(5));
    }
}
