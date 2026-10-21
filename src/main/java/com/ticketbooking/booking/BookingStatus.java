package com.ticketbooking.booking;

/**
 * A booking only exists once a hold has been confirmed and paid, so its lifecycle is short:
 * <pre>  CONFIRMED --cancel--> CANCELLED (terminal)</pre>
 * The "pending" phase of a purchase is modelled by an ACTIVE {@link SeatHold}.
 */
public enum BookingStatus {
    CONFIRMED,
    CANCELLED
}
