package com.ticketbooking.booking;

import com.ticketbooking.booking.BookingDtos.CreateHoldRequest;
import com.ticketbooking.booking.BookingDtos.HoldResponse;
import com.ticketbooking.idempotency.IdempotencyService;
import com.ticketbooking.idempotency.IdempotentResponses;
import com.ticketbooking.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@Tag(name = "Seat holds")
@SecurityRequirement(name = "bearerAuth")
public class HoldController {

    private final HoldService holdService;
    private final IdempotencyService idempotencyService;

    public HoldController(HoldService holdService, IdempotencyService idempotencyService) {
        this.holdService = holdService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping("/api/v1/screenings/{screeningId}/holds")
    @Operation(summary = "Temporarily hold seats (default 5 minutes). All-or-nothing; 409 if any seat is taken.")
    public ResponseEntity<HoldResponse> create(
            @PathVariable Long screeningId,
            @Parameter(description = "Client-generated unique key (8-100 chars) for safe retries")
            @RequestHeader(IdempotentResponses.HEADER) String idempotencyKey,
            @Valid @RequestBody CreateHoldRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        Object fingerprint = Map.of("screeningId", screeningId,
                "seatIds", request.screeningSeatIds().stream().sorted().toList());
        var result = idempotencyService.execute(user.id(), idempotencyKey, "CREATE_HOLD", fingerprint,
                HoldResponse.class, HttpStatus.CREATED.value(),
                () -> holdService.createHold(user.id(), screeningId, request));
        return IdempotentResponses.toResponse(result);
    }

    @GetMapping("/api/v1/holds/{holdId}")
    @Operation(summary = "Get one of your holds")
    public HoldResponse get(@PathVariable UUID holdId, @AuthenticationPrincipal AuthenticatedUser user) {
        return holdService.get(user.id(), holdId);
    }

    @DeleteMapping("/api/v1/holds/{holdId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Release one of your active holds before it expires")
    public void release(@PathVariable UUID holdId, @AuthenticationPrincipal AuthenticatedUser user) {
        holdService.release(user.id(), holdId);
    }
}
