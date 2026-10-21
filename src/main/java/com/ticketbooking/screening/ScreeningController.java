package com.ticketbooking.screening;

import com.ticketbooking.screening.ScreeningDtos.AssignSeatsRequest;
import com.ticketbooking.screening.ScreeningDtos.AssignSeatsResponse;
import com.ticketbooking.screening.ScreeningDtos.CreateScreeningRequest;
import com.ticketbooking.screening.ScreeningDtos.ScreeningResponse;
import com.ticketbooking.screening.ScreeningDtos.SeatMapResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Screenings")
public class ScreeningController {

    private final ScreeningService screeningService;

    public ScreeningController(ScreeningService screeningService) {
        this.screeningService = screeningService;
    }

    @GetMapping("/api/v1/screenings/{screeningId}")
    @Operation(summary = "Screening details with live seat counts (public)")
    public ScreeningResponse get(@PathVariable Long screeningId) {
        return screeningService.get(screeningId);
    }

    @GetMapping("/api/v1/screenings/{screeningId}/seats")
    @Operation(summary = "Seat map with per-seat availability (public). Expired holds are shown as AVAILABLE.")
    public SeatMapResponse seats(@PathVariable Long screeningId) {
        return screeningService.seatMap(screeningId);
    }

    @PostMapping("/api/v1/admin/screenings")
    @ResponseStatus(HttpStatus.CREATED)
    @Tag(name = "Admin")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Schedule a screening of an event at a venue (ADMIN). Overlaps in the same venue are rejected.")
    public ScreeningResponse create(@Valid @RequestBody CreateScreeningRequest request) {
        return screeningService.create(request);
    }

    @PostMapping("/api/v1/admin/screenings/{screeningId}/seats")
    @ResponseStatus(HttpStatus.CREATED)
    @Tag(name = "Admin")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Assign venue seats and prices to a screening (ADMIN)")
    public AssignSeatsResponse assignSeats(@PathVariable Long screeningId, @Valid @RequestBody AssignSeatsRequest request) {
        return screeningService.assignSeats(screeningId, request);
    }
}
