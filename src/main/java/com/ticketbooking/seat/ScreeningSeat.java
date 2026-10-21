package com.ticketbooking.seat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Seat inventory for one screening. Rows are created by an admin when seats are
 * assigned to a screening; afterwards their state is changed ONLY through the
 * conditional UPDATE statements in {@link ScreeningSeatRepository}, never by
 * dirty-checking this entity. That keeps every state transition a single atomic
 * compare-and-set in PostgreSQL.
 */
@Entity
@Table(name = "screening_seats")
public class ScreeningSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "screening_id", nullable = false, updatable = false)
    private Long screeningId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id", nullable = false, updatable = false)
    private Seat seat;

    @Column(nullable = false, updatable = false)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private SeatStatus status;

    @Column(name = "hold_id", updatable = false)
    private UUID holdId;

    @Column(name = "held_until", updatable = false)
    private Instant heldUntil;

    @Column(name = "booking_id", updatable = false)
    private UUID bookingId;

    @Column(nullable = false, updatable = false)
    private long version;

    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;

    protected ScreeningSeat() {
    }

    public static ScreeningSeat available(Long screeningId, Seat seat, BigDecimal price, Instant now) {
        ScreeningSeat ss = new ScreeningSeat();
        ss.screeningId = screeningId;
        ss.seat = seat;
        ss.price = price;
        ss.status = SeatStatus.AVAILABLE;
        ss.updatedAt = now;
        return ss;
    }

    /** A HELD seat whose hold has passed its expiry is effectively available to new customers. */
    public SeatStatus effectiveStatus(Instant now) {
        if (status == SeatStatus.HELD && heldUntil != null && !heldUntil.isAfter(now)) {
            return SeatStatus.AVAILABLE;
        }
        return status;
    }

    public Long getId() { return id; }
    public Long getScreeningId() { return screeningId; }
    public Seat getSeat() { return seat; }
    public BigDecimal getPrice() { return price; }
    public SeatStatus getStatus() { return status; }
    public UUID getHoldId() { return holdId; }
    public Instant getHeldUntil() { return heldUntil; }
    public UUID getBookingId() { return bookingId; }
    public long getVersion() { return version; }
}
