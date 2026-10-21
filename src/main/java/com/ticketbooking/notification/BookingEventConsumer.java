package com.ticketbooking.notification;

import com.ticketbooking.common.BookingMetrics;
import com.ticketbooking.common.CorrelationIdFilter;
import com.ticketbooking.messaging.EventEnvelope;
import com.ticketbooking.messaging.EventValidationException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka listener for booking events. Offsets are committed by the container only
 * after this method returns normally (ack-mode RECORD, auto-commit disabled), i.e.
 * after the database transaction committed. A crash in between causes redelivery,
 * which the processed_events table turns into a no-op.
 */
@Component
public class BookingEventConsumer {

    private final NotificationService notificationService;
    private final BookingMetrics metrics;

    public BookingEventConsumer(NotificationService notificationService, BookingMetrics metrics) {
        this.notificationService = notificationService;
        this.metrics = metrics;
    }

    @KafkaListener(id = "booking-notifications", topics = "${app.kafka.topics.booking-events}")
    public void onBookingEvent(ConsumerRecord<String, String> record) {
        EventEnvelope event;
        try {
            event = notificationService.parse(record.value());
        } catch (EventValidationException invalid) {
            metrics.consumerError("validation");
            throw invalid;   // not retryable -> dead-letter topic
        }
        if (event.correlationId() != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, event.correlationId());
        }
        try {
            notificationService.handle(event);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
