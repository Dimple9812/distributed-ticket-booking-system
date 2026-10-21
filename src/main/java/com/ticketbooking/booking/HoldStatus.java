package com.ticketbooking.booking;

/**
 * <pre>
 *   ACTIVE --confirm--> CONFIRMED
 *   ACTIVE --release--> RELEASED
 *   ACTIVE --expiry --> EXPIRED
 * </pre>
 * Every non-ACTIVE state is terminal; a hold can never be re-activated.
 */
public enum HoldStatus {
    ACTIVE,
    CONFIRMED,
    RELEASED,
    EXPIRED
}
