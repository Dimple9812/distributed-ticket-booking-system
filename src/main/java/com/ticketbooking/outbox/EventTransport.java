package com.ticketbooking.outbox;

/**
 * Where the outbox publisher delivers events. {@link #send} must return only once the
 * message has been durably accepted (Kafka broker ack, or the in-process consumer's
 * transaction committed); throwing means "not delivered, retry later".
 */
public interface EventTransport {

    void send(OutboxMessage message) throws Exception;

    record OutboxMessage(java.util.UUID eventId, String topic, String key, String eventType, int eventVersion,
                         String correlationId, String payload) {
    }
}
