package com.ticketbooking.booking;

import com.ticketbooking.booking.BookingDtos.BookingResponse;
import com.ticketbooking.booking.BookingDtos.BookingSummary;
import com.ticketbooking.booking.BookingDtos.ConfirmBookingRequest;
import com.ticketbooking.common.PageResponse;
import com.ticketbooking.idempotency.IdempotencyService;
import com.ticketbooking.idempotency.IdempotentResponses;
import com.ticketbooking.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bookings")
@Tag(name = "Bookings")
@SecurityRequirement(name = "bearerAuth")
public class BookingController {

    private final BookingService bookingService;
    private final IdempotencyService idempotencyService;

    public BookingController(BookingService bookingService, IdempotencyService idempotencyService) {
        this.bookingService = bookingService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping("/confirm")
    @Operation(summary = "Confirm (pay for) an active hold. Creates the booking and publishes BookingConfirmed.")
    public ResponseEntity<BookingResponse> confirm(
            @Parameter(description = "Client-generated unique key (8-100 chars) for safe retries")
            @RequestHeader(IdempotentResponses.HEADER) String idempotencyKey,
            @Valid @RequestBody ConfirmBookingRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        var result = idempotencyService.execute(user.id(), idempotencyKey, "CONFIRM_BOOKING",
                Map.of("holdId", request.holdId().toString(), "paymentMethodToken", request.paymentMethodToken()),
                BookingResponse.class, HttpStatus.CREATED.value(), () -> bookingService.confirm(user.id(), request));
        return IdempotentResponses.toResponse(result);
    }

    @GetMapping
    @Operation(summary = "Your booking history (newest first)")
    public PageResponse<BookingSummary> list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                             @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                             @AuthenticationPrincipal AuthenticatedUser user) {
        return bookingService.list(user.id(), PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @GetMapping("/{bookingId}")
    @Operation(summary = "One of your bookings. Other customers' bookings return 404.")
    public BookingResponse get(@PathVariable UUID bookingId, @AuthenticationPrincipal AuthenticatedUser user) {
        return bookingService.get(user.id(), bookingId);
    }

    @PostMapping("/{bookingId}/cancel")
    @Operation(summary = "Cancel a confirmed booking (until the cut-off before the show). Idempotency-Key is optional.")
    public ResponseEntity<BookingResponse> cancel(
            @PathVariable UUID bookingId,
            @RequestHeader(value = IdempotentResponses.HEADER, required = false) String idempotencyKey,
            @AuthenticationPrincipal AuthenticatedUser user) {
        if (idempotencyKey == null) {
            return ResponseEntity.ok(bookingService.cancel(user.id(), bookingId));
        }
        var result = idempotencyService.execute(user.id(), idempotencyKey, "CANCEL_BOOKING",
                Map.of("bookingId", bookingId.toString()), BookingResponse.class, HttpStatus.OK.value(),
                () -> bookingService.cancel(user.id(), bookingId));
        return IdempotentResponses.toResponse(result);
    }
}
