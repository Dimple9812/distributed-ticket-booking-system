package com.ticketbooking.booking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * One booked seat. {@code active = true} rows are covered by the partial unique index
 * {@code ux_booking_items_active_seat}: PostgreSQL itself refuses a second active
 * booking of the same screening seat.
 */
@Entity
@Table(name = "booking_items")
public class BookingItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false, updatable = false)
    private Booking booking;

    @Column(name = "screening_seat_id", nullable = false, updatable = false)
    private Long screeningSeatId;

    @Column(nullable = false, updatable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private boolean active;

    protected BookingItem() {
    }

    BookingItem(Booking booking, Long screeningSeatId, BigDecimal price) {
        this.booking = booking;
        this.screeningSeatId = screeningSeatId;
        this.price = price;
        this.active = true;
    }

    void deactivate() {
        this.active = false;
    }

    public Long getId() { return id; }
    public Long getScreeningSeatId() { return screeningSeatId; }
    public BigDecimal getPrice() { return price; }
    public boolean isActive() { return active; }
}
