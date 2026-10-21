package com.ticketbooking.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketbooking.common.BookingMetrics;
import com.ticketbooking.common.PageResponse;
import com.ticketbooking.messaging.BookingEvents;
import com.ticketbooking.messaging.BookingEvents.BookingCancelledV1;
import com.ticketbooking.messaging.BookingEvents.BookingConfirmedV1;
import com.ticketbooking.messaging.BookingEvents.BookingExpiredV1;
import com.ticketbooking.messaging.EventEnvelope;
import com.ticketbooking.messaging.EventValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

/**
 * Idempotent consumer logic. The processed_events insert, the notification rows and
 * therefore the "this event has been handled" fact all commit in one transaction.
 * A redelivered event (at-least-once delivery) hits the primary key on
 * processed_events and is skipped: duplicates produce no duplicate business effect.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final JdbcTemplate jdbc;
    private final NotificationRepository repository;
    private final NotificationSender sender;
    private final ObjectMapper objectMapper;
    private final BookingMetrics metrics;
    private final Clock clock;
    private final String consumerGroup;

    public NotificationService(JdbcTemplate jdbc, NotificationRepository repository, NotificationSender sender,
                               ObjectMapper objectMapper, BookingMetrics metrics, Clock clock,
                               @Value("${spring.kafka.consumer.group-id}") String consumerGroup) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.sender = sender;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.clock = clock;
        this.consumerGroup = consumerGroup;
    }

    /** Parses and validates a raw record value. Throws {@link EventValidationException} for poison messages. */
    public EventEnvelope parse(String value) {
        if (value == null || value.isBlank()) {
            throw new EventValidationException("Empty message");
        }
        EventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(value, EventEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new EventValidationException("Message is not a valid event envelope", e);
        }
        if (envelope.eventId() == null || envelope.eventType() == null || envelope.payload() == null
                || envelope.payload().isNull()) {
            throw new EventValidationException("Event envelope is missing eventId, eventType or payload");
        }
        if (envelope.eventVersion() != BookingEvents.VERSION_1 || !BookingEvents.SUPPORTED_V1.contains(envelope.eventType())) {
            throw new EventValidationException("Unsupported event " + envelope.eventType() + " v" + envelope.eventVersion());
        }
        return envelope;
    }

    /** @return true if processed now, false if it was a duplicate */
    @Transactional
    public boolean handle(EventEnvelope event) {
        Instant now = clock.instant();
        int firstTime = jdbc.update("""
                insert into processed_events (consumer_group, event_id, event_type, processed_at)
                values (?, ?, ?, ?) on conflict do nothing
                """, consumerGroup, event.eventId(), event.eventType(), Timestamp.from(now));
        if (firstTime == 0) {
            log.info("Duplicate event {} ({}) ignored", event.eventId(), event.eventType());
            return false;
        }
        Message message = render(event);
        repository.save(new Notification(message.userId(), event.eventId(), event.eventType(),
                Notification.CHANNEL_IN_APP, message.title(), message.body(), now));
        // Mock email. If it throws, the whole transaction (including processed_events) rolls back
        // and the record is retried, then dead-lettered.
        sender.sendEmail(message.email(), message.title(), message.body());
        repository.save(new Notification(message.userId(), event.eventId(), event.eventType(),
                Notification.CHANNEL_EMAIL, message.title(), message.body(), now));
        log.info("Notified user {} about {} {}", message.userId(), event.eventType(), event.aggregateId());
        return true;
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(Long userId, Pageable pageable) {
        return PageResponse.from(repository.findByUserIdAndChannel(userId, Notification.CHANNEL_IN_APP, pageable),
                n -> new NotificationResponse(n.getId(), n.getType(), n.getTitle(), n.getMessage(), n.getCreatedAt()));
    }

    private Message render(EventEnvelope event) {
        try {
            return switch (event.eventType()) {
                case BookingEvents.BOOKING_CONFIRMED -> {
                    var p = objectMapper.treeToValue(event.payload(), BookingConfirmedV1.class);
                    require(p.userId(), p.userEmail());
                    yield new Message(p.userId(), p.userEmail(), "Booking confirmed: " + p.eventTitle(),
                            "Your booking " + p.bookingId() + " for " + p.eventTitle() + " at " + p.venueName() + " ("
                                    + p.startsAt() + ") is confirmed. Seats: " + String.join(", ", p.seats())
                                    + ". Total: " + p.totalAmount() + " " + p.currency() + ".");
                }
                case BookingEvents.BOOKING_CANCELLED -> {
                    var p = objectMapper.treeToValue(event.payload(), BookingCancelledV1.class);
                    require(p.userId(), p.userEmail());
                    yield new Message(p.userId(), p.userEmail(), "Booking cancelled: " + p.eventTitle(),
                            "Booking " + p.bookingId() + " was cancelled. A refund of " + p.refundAmount() + " "
                                    + p.currency() + " has been issued.");
                }
                case BookingEvents.BOOKING_EXPIRED -> {
                    var p = objectMapper.treeToValue(event.payload(), BookingExpiredV1.class);
                    require(p.userId(), p.userEmail());
                    yield new Message(p.userId(), p.userEmail(), "Seat hold expired: " + p.eventTitle(),
                            "Your hold on seats " + String.join(", ", p.seats())
                                    + " expired before payment. The seats have been released.");
                }
                default -> throw new EventValidationException("Unsupported event type " + event.eventType());
            };
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new EventValidationException("Invalid payload for " + event.eventType(), e);
        }
    }

    private static void require(Long userId, String email) {
        if (userId == null || email == null || email.isBlank()) {
            throw new EventValidationException("Payload is missing userId or userEmail");
        }
    }

    private record Message(Long userId, String email, String title, String body) {
    }

    public record NotificationResponse(Long id, String type, String title, String message, Instant createdAt) {
    }
}
