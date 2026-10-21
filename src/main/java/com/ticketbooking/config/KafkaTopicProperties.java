package com.ticketbooking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.kafka")
public record KafkaTopicProperties(Topics topics, int partitions, short replicationFactor, Consumer consumer) {

    public record Topics(String bookingEvents, String bookingEventsDlt) {
    }

    public record Consumer(int retryAttempts, Duration retryInitialInterval) {
    }
}
