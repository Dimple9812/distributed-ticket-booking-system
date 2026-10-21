package com.ticketbooking.messaging;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire format of every booking event. {@code eventId} is generated once when the
 * outbox row is written and never changes, so it survives re-publication and is
 * the consumer's de-duplication key. {@code eventVersion} versions the payload schema.
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String aggregateType,
        String aggregateId,
        String correlationId,
        JsonNode payload) {
}
