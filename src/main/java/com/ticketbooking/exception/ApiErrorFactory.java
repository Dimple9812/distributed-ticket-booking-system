package com.ticketbooking.exception;

import com.ticketbooking.common.CorrelationIdFilter;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Builds {@link ApiError} bodies; shared by MVC exception handling and security handlers. */
public final class ApiErrorFactory {

    private ApiErrorFactory() {
    }

    public static ApiError build(ErrorCode code, String message, String path,
                                 Map<String, Object> details, List<ApiError.FieldViolation> fieldErrors) {
        return new ApiError(
                Instant.now(Clock.systemUTC()),
                code.status().value(),
                code.status().getReasonPhrase(),
                code.name(),
                message,
                path,
                MDC.get(CorrelationIdFilter.MDC_KEY),
                details,
                fieldErrors);
    }

    public static ApiError build(ErrorCode code, String message, String path) {
        return build(code, message, path, Map.of(), List.of());
    }
}
