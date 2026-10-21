package com.ticketbooking.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Consistent error body returned by every endpoint, including security failures. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        String correlationId,
        Map<String, Object> details,
        List<FieldViolation> fieldErrors) {

    public record FieldViolation(String field, String message) {
    }
}
