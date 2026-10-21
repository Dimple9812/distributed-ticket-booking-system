package com.ticketbooking.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Versioned event contracts published to the {@code booking-events} topic.
 * Changing a payload incompatibly requires a new version (e.g. BookingConfirmed v2);
 * consumers reject versions they do not understand (they go to the DLT for review).
 */
public final class BookingEvents {

    public static final String AGGREGATE_BOOKING = "Booking";
    public static final String AGGREGATE_HOLD = "SeatHold";

    public static final String BOOKING_CONFIRMED = "BookingConfirmed";
    public static final String BOOKING_CANCELLED = "BookingCancelled";
    public static final String BOOKING_EXPIRED = "BookingExpired";

    public static final int VERSION_1 = 1;

    /** Event types and versions this service knows how to consume. */
    public static final Set<String> SUPPORTED_V1 = Set.of(BOOKING_CONFIRMED, BOOKING_CANCELLED, BOOKING_EXPIRED);

    private BookingEvents() {
    }

    public record BookingConfirmedV1(UUID bookingId, UUID holdId, Long userId, String userEmail, Long screeningId,
                                     String eventTitle, String venueName, Instant startsAt, List<String> seats,
                                     BigDecimal totalAmount, String currency) {
    }

    public record BookingCancelledV1(UUID bookingId, Long userId, String userEmail, Long screeningId, String eventTitle,
                                     List<String> seats, BigDecimal refundAmount, String currency) {
    }

    /** A hold (pending booking) expired before it was confirmed; its seats were released. */
    public record BookingExpiredV1(UUID holdId, Long userId, String userEmail, Long screeningId, String eventTitle,
                                   List<String> seats, Instant expiredAt) {
    }
}
