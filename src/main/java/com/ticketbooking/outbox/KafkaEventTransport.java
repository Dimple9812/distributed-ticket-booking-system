package com.ticketbooking.outbox;

import com.ticketbooking.config.OutboxProperties;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Default transport: publish to Kafka and block until the broker acknowledges (acks=all). */
@Component
@ConditionalOnProperty(prefix = "app.messaging", name = "transport", havingValue = "kafka", matchIfMissing = true)
public class KafkaEventTransport implements EventTransport {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;

    public KafkaEventTransport(KafkaTemplate<String, String> kafkaTemplate, OutboxProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @Override
    public void send(OutboxMessage m) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(m.topic(), m.key(), m.payload());
        record.headers().add("eventId", m.eventId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", m.eventType().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventVersion", String.valueOf(m.eventVersion()).getBytes(StandardCharsets.UTF_8));
        if (m.correlationId() != null) {
            record.headers().add("correlationId", m.correlationId().getBytes(StandardCharsets.UTF_8));
        }
        try {
            kafkaTemplate.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }
}
