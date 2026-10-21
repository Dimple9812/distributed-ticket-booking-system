package com.ticketbooking.seat;

/**
 * Per-screening seat state.
 * <pre>
 *   AVAILABLE --hold--> HELD --confirm--> BOOKED --cancel--> AVAILABLE
 *                        |
 *                        +--release / expiry--> AVAILABLE
 * </pre>
 * Transitions are performed with conditional UPDATE statements
 * ({@code WHERE status = ... AND hold_id = ...}) so a stale actor cannot
 * overwrite a newer state.
 */
public enum SeatStatus {
    AVAILABLE,
    HELD,
    BOOKED
}
