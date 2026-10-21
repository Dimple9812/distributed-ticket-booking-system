package com.ticketbooking.venue;

import com.ticketbooking.seat.Seat;
import com.ticketbooking.seat.SeatType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class VenueDtos {

    private VenueDtos() {
    }

    public record CreateVenueRequest(
            @NotBlank @Size(max = 150) @Schema(example = "PVR Rave 3") String name,
            @NotBlank @Size(max = 100) @Schema(example = "Kanpur") String city,
            @NotBlank @Size(max = 300) @Schema(example = "Mall Road, Kanpur") String address,
            @NotEmpty @Size(max = 50) List<@Valid RowLayout> rows) {
    }

    public record RowLayout(
            @NotBlank @Pattern(regexp = "[A-Z]{1,2}", message = "row label must be 1-2 uppercase letters")
            @Schema(example = "A") String rowLabel,
            @Min(1) @Max(100) @Schema(example = "12") int seatCount,
            @NotNull SeatType seatType) {
    }

    public record SeatResponse(Long id, String rowLabel, int seatNumber, SeatType seatType) {
        public static SeatResponse from(Seat seat) {
            return new SeatResponse(seat.getId(), seat.getRowLabel(), seat.getSeatNumber(), seat.getSeatType());
        }
    }

    public record VenueResponse(Long id, String name, String city, String address, int seatCount,
                                List<SeatResponse> seats) {
    }
}
