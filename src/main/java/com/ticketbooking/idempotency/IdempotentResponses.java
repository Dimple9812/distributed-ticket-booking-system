package com.ticketbooking.idempotency;

import org.springframework.http.ResponseEntity;

public final class IdempotentResponses {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private IdempotentResponses() {
    }

    public static <T> ResponseEntity<T> toResponse(IdempotencyService.IdempotentResult<T> result) {
        return ResponseEntity.status(result.status())
                .header(REPLAYED_HEADER, String.valueOf(result.replayed()))
                .body(result.body());
    }
}
