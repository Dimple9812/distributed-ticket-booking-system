package com.ticketbooking.booking.lock;

import com.ticketbooking.exception.ApiException;
import com.ticketbooking.exception.ErrorCode;

import java.util.List;
import java.util.Map;

/** A Redis seat lock is owned by someone else. Carries the observed value so callers can detect stale locks. */
public class LockConflictException extends ApiException {

    private final Long seatId;
    private final transient String currentHolder;

    public LockConflictException(Long seatId, String currentHolder) {
        super(ErrorCode.SEAT_UNAVAILABLE, "One or more seats are currently unavailable",
                Map.of("unavailableSeatIds", List.of(seatId)));
        this.seatId = seatId;
        this.currentHolder = currentHolder;
    }

    public Long getSeatId() {
        return seatId;
    }

    public String getCurrentHolder() {
        return currentHolder;
    }
}
