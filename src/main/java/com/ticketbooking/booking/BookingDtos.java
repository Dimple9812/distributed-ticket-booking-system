package com.ticketbooking.booking;

import com.ticketbooking.seat.SeatType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class BookingDtos {

    private BookingDtos() {
    }

    public record CreateHoldRequest(
            @NotEmpty @Size(max = 50) @Schema(description = "screeningSeatId values from GET /screenings/{id}/seats",
                    example = "[101, 102]") List<@NotNull @Positive Long> screeningSeatIds) {
    }

    public record SeatLine(Long screeningSeatId, String seatLabel, SeatType seatType, BigDecimal price) {
    }

    public record HoldResponse(UUID holdId, Long screeningId, HoldStatus status, Instant expiresAt,
                               List<SeatLine> seats, BigDecimal totalAmount, String currency) {
    }

    public record ConfirmBookingRequest(
            @NotNull UUID holdId,
            @NotNull @Pattern(regexp = "pm_[a-z0-9_]{3,50}", message = "must be a payment method token such as pm_card_visa")
            @Schema(description = "Opaque token from the (simulated) payment provider. pm_card_declined simulates a decline.",
                    example = "pm_card_visa") String paymentMethodToken) {
    }

    public record ScreeningInfo(Long screeningId, String eventTitle, String venueName, String city, Instant startsAt) {
    }

    public record BookingResponse(UUID bookingId, UUID holdId, BookingStatus status, ScreeningInfo screening,
                                  List<SeatLine> seats, BigDecimal totalAmount, String currency,
                                  String paymentReference, Instant confirmedAt, Instant cancelledAt) {
    }

    public record BookingSummary(UUID bookingId, BookingStatus status, Long screeningId, BigDecimal totalAmount,
                                 String currency, int seatCount, Instant confirmedAt, Instant cancelledAt) {
    }
}
