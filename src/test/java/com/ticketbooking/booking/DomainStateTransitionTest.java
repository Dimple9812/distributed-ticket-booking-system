package com.ticketbooking.booking;

import com.ticketbooking.exception.ApiException;
import com.ticketbooking.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainStateTransitionTest {

    private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");

    @Test
    void holdLeavesActiveExactlyOnce() {
        SeatHold hold = new SeatHold(UUID.randomUUID(), 1L, 2L, List.of(3L, 4L), NOW.plusSeconds(300), NOW);
        hold.transitionTo(HoldStatus.CONFIRMED, NOW);
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.CONFIRMED);

        for (HoldStatus target : HoldStatus.values()) {
            assertThatThrownBy(() -> hold.transitionTo(target, NOW))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.HOLD_NOT_ACTIVE));
        }
    }

    @Test
    void holdCannotBeReactivated() {
        SeatHold hold = new SeatHold(UUID.randomUUID(), 1L, 2L, List.of(3L), NOW.plusSeconds(300), NOW);
        assertThatThrownBy(() -> hold.transitionTo(HoldStatus.ACTIVE, NOW)).isInstanceOf(ApiException.class);
    }

    @Test
    void holdExpiryIsInclusiveOfTheDeadline() {
        SeatHold hold = new SeatHold(UUID.randomUUID(), 1L, 2L, List.of(3L), NOW.plusSeconds(300), NOW);
        assertThat(hold.isExpiredAt(NOW.plusSeconds(299))).isFalse();
        assertThat(hold.isExpiredAt(NOW.plusSeconds(300))).isTrue();
    }

    @Test
    void cancelledBookingCannotBeCancelledAgainAndDeactivatesItsItems() {
        Booking booking = new Booking(UUID.randomUUID(), 1L, 2L, UUID.randomUUID(), new BigDecimal("500.00"), "INR",
                "sim_ch_1", NOW);
        booking.addItem(10L, new BigDecimal("250.00"));
        booking.addItem(11L, new BigDecimal("250.00"));

        booking.cancel(NOW);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(booking.getCancelledAt()).isEqualTo(NOW);
        assertThat(booking.getItems()).noneMatch(BookingItem::isActive);
        assertThatThrownBy(() -> booking.cancel(NOW))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.BOOKING_NOT_CANCELLABLE));
    }
}
