package com.ticketbooking.exception;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error codes returned in every error response.
 * Clients should branch on {@code code}, never on the human-readable message.
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    IDEMPOTENCY_KEY_MISSING(HttpStatus.BAD_REQUEST),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED),
    AUTHENTICATION_FAILED(HttpStatus.UNAUTHORIZED),
    PAYMENT_DECLINED(HttpStatus.PAYMENT_REQUIRED),
    ACCESS_DENIED(HttpStatus.FORBIDDEN),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT),
    SEAT_UNAVAILABLE(HttpStatus.CONFLICT),
    SCREENING_NOT_BOOKABLE(HttpStatus.CONFLICT),
    SCREENING_OVERLAP(HttpStatus.CONFLICT),
    HOLD_NOT_ACTIVE(HttpStatus.CONFLICT),
    BOOKING_NOT_CANCELLABLE(HttpStatus.CONFLICT),
    IDEMPOTENCY_REQUEST_IN_PROGRESS(HttpStatus.CONFLICT),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT),
    HOLD_EXPIRED(HttpStatus.GONE),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY),
    INVALID_SEAT_SELECTION(HttpStatus.UNPROCESSABLE_ENTITY),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
    LOCK_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
