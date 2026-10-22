package com.ticketbooking.notification;

import com.ticketbooking.common.CorrelationIdFilter;
import com.ticketbooking.messaging.EventEnvelope;
import com.ticketbooking.outbox.EventTransport;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Transport for deployments without a Kafka broker (e.g. a free-tier demo host):
 * the outbox publisher hands each event straight to the same idempotent notification
 * handler the Kafka consumer uses. The outbox still provides durability, retries with
 * back-off and parking of failing events; processed_events still de-duplicates.
 * What is lost is decoupling: producer and consumer run in one process.
 */
@Component
@ConditionalOnProperty(prefix = "app.messaging", name = "transport", havingValue = "in-process")
public class InProcessEventTransport implements EventTransport {

    private final NotificationService notificationService;

    public InProcessEventTransport(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override
    public void send(OutboxMessage message) {
        EventEnvelope event = notificationService.parse(message.payload());
        if (message.correlationId() != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, message.correlationId());
        }
        try {
            notificationService.handle(event);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
