package com.ticketbooking.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.ticketbooking.common.BookingMetrics;
import com.ticketbooking.messaging.EventValidationException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class EventValidationTest {

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final NotificationService service = new NotificationService(mock(JdbcTemplate.class),
            mock(NotificationRepository.class), mock(NotificationSender.class), mapper,
            new BookingMetrics(new SimpleMeterRegistry()), Clock.systemUTC(), "group");

    @Test
    void acceptsAWellFormedV1Event() {
        String json = """
                {"eventId":"9b2f3c1e-1111-4a6e-9a4e-000000000001","eventType":"BookingConfirmed","eventVersion":1,
                 "occurredAt":"2026-10-21T10:00:00Z","aggregateType":"Booking","aggregateId":"x","payload":{"userId":1}}
                """;
        assertThat(service.parse(json).eventType()).isEqualTo("BookingConfirmed");
    }

    @Test
    void rejectsPoisonMessages() {
        assertThatThrownBy(() -> service.parse("{not json")).isInstanceOf(EventValidationException.class);
        assertThatThrownBy(() -> service.parse("")).isInstanceOf(EventValidationException.class);
        assertThatThrownBy(() -> service.parse("{\"eventType\":\"BookingConfirmed\",\"eventVersion\":1}"))
                .isInstanceOf(EventValidationException.class);   // no eventId / payload
        assertThatThrownBy(() -> service.parse("""
                {"eventId":"9b2f3c1e-1111-4a6e-9a4e-000000000001","eventType":"BookingConfirmed","eventVersion":2,
                 "payload":{}}""")).isInstanceOf(EventValidationException.class);   // unknown version
        assertThatThrownBy(() -> service.parse("""
                {"eventId":"9b2f3c1e-1111-4a6e-9a4e-000000000001","eventType":"SomethingElse","eventVersion":1,
                 "payload":{}}""")).isInstanceOf(EventValidationException.class);   // unknown type
    }
}
