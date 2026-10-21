package com.ticketbooking.booking;

import com.ticketbooking.common.AssignedIdEntity;
import com.ticketbooking.exception.ApiException;
import com.ticketbooking.exception.ErrorCode;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "bookings")
public class Booking extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "screening_id", nullable = false, updatable = false)
    private Long screeningId;

    @Column(name = "hold_id", nullable = false, updatable = false, unique = true)
    private UUID holdId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    @Column(name = "total_amount", nullable = false, updatable = false)
    private BigDecimal totalAmount;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(name = "payment_reference", nullable = false, updatable = false)
    private String paymentReference;

    @Column(name = "confirmed_at", nullable = false, updatable = false)
    private Instant confirmedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = false)
    @OrderBy("screeningSeatId")
    @BatchSize(size = 50)
    private List<BookingItem> items = new ArrayList<>();

    protected Booking() {
    }

    public Booking(UUID id, Long userId, Long screeningId, UUID holdId, BigDecimal totalAmount, String currency,
                   String paymentReference, Instant now) {
        this.id = id;
        this.userId = userId;
        this.screeningId = screeningId;
        this.holdId = holdId;
        this.status = BookingStatus.CONFIRMED;
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.paymentReference = paymentReference;
        this.confirmedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void addItem(Long screeningSeatId, BigDecimal price) {
        items.add(new BookingItem(this, screeningSeatId, price));
    }

    /** CONFIRMED -> CANCELLED. A cancelled booking can never return to CONFIRMED. */
    public void cancel(Instant now) {
        if (status != BookingStatus.CONFIRMED) {
            throw new ApiException(ErrorCode.BOOKING_NOT_CANCELLABLE, "Booking is already " + status);
        }
        status = BookingStatus.CANCELLED;
        cancelledAt = now;
        updatedAt = now;
        items.forEach(BookingItem::deactivate);
    }

    @Override
    public UUID getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getScreeningId() { return screeningId; }
    public UUID getHoldId() { return holdId; }
    public BookingStatus getStatus() { return status; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public String getCurrency() { return currency; }
    public String getPaymentReference() { return paymentReference; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public Instant getCreatedAt() { return createdAt; }
    public List<BookingItem> getItems() { return items; }
}
