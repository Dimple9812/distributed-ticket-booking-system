package com.ticketbooking.screening;

import com.ticketbooking.seat.SeatStatus;
import com.ticketbooking.seat.SeatType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ScreeningDtos {

    private ScreeningDtos() {
    }

    public record CreateScreeningRequest(
            @NotNull @Positive Long eventId,
            @NotNull @Positive Long venueId,
            @NotNull @Future @Schema(example = "2026-12-24T14:30:00Z") Instant startsAt) {
    }

    /**
     * Assigns venue seats to a screening with a price per seat type.
     * {@code seatIds} is optional: when omitted, every seat of the venue is assigned.
     */
    public record AssignSeatsRequest(
            @Size(max = 2000) List<@NotNull @Positive Long> seatIds,
            @NotEmpty Map<@NotNull SeatType,
                    @NotNull @DecimalMin(value = "1.00") @DecimalMax(value = "100000.00") @Digits(integer = 8, fraction = 2)
                            BigDecimal> priceBySeatType) {
    }

    public record AssignSeatsResponse(Long screeningId, int assignedSeats, int totalSeats) {
    }

    public record ScreeningResponse(Long id, Long eventId, String eventTitle, Long venueId, String venueName,
                                    String city, Instant startsAt, Instant endsAt, ScreeningStatus status,
                                    long totalSeats, long availableSeats) {
    }

    public record SeatAvailability(Long screeningSeatId, Long seatId, String rowLabel, int seatNumber,
                                   SeatType seatType, BigDecimal price, SeatStatus status) {
    }

    public record SeatMapResponse(Long screeningId, Map<SeatStatus, Long> summary, List<SeatAvailability> seats) {
    }
}
