package com.ticketbooking.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketbooking.common.CorrelationIdFilter;
import com.ticketbooking.config.KafkaTopicProperties;
import com.ticketbooking.messaging.EventEnvelope;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Writes an event to the outbox table. {@code Propagation.MANDATORY} makes it
 * impossible to call outside a transaction: the event row must commit or roll back
 * atomically with the business change that produced it. That is the whole point of
 * the transactional outbox - no "DB committed but event lost" and no "event sent
 * for a change that rolled back".
 */
@Component
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final KafkaTopicProperties topics;
    private final Clock clock;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper objectMapper, KafkaTopicProperties topics, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.topics = topics;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String aggregateType, String aggregateId, String eventType, int eventVersion, Object payload) {
        UUID eventId = UUID.randomUUID();
        Instant now = clock.instant();
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        EventEnvelope envelope = new EventEnvelope(eventId, eventType, eventVersion, now, aggregateType, aggregateId,
                correlationId, objectMapper.valueToTree(payload));
        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise outbox event " + eventType, e);
        }
        jdbc.update("""
                insert into outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, topic, payload,
                                           correlation_id, status, attempts, next_attempt_at, created_at)
                values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'PENDING', 0, ?, ?)
                """, eventId, aggregateType, aggregateId, eventType, eventVersion, topics.topics().bookingEvents(), json,
                correlationId, Timestamp.from(now), Timestamp.from(now));
        return eventId;
    }
}
