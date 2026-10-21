package com.ticketbooking.exception;

import java.util.Map;

/**
 * Single exception type for expected business failures. The {@link ErrorCode}
 * determines the HTTP status; {@code details} carries structured context
 * (for example the seat ids that were unavailable).
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> details;

    public ApiException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public ApiException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public ApiException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = Map.of();
    }

    public ErrorCode getCode() {
        return code;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public static ApiException notFound(String resource, Object id) {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, resource + " " + id + " was not found");
    }
}
