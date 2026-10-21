package com.ticketbooking.payment;

import com.ticketbooking.common.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_attempts")
public class PaymentAttempt extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "hold_id", nullable = false)
    private UUID holdId;

    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "provider_reference")
    private String providerReference;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PaymentAttempt() {
    }

    public PaymentAttempt(Long userId, UUID holdId, UUID bookingId, BigDecimal amount, String currency,
                          PaymentStatus status, String providerReference, String failureReason, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.holdId = holdId;
        this.bookingId = bookingId;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.providerReference = providerReference;
        this.failureReason = failureReason;
        this.createdAt = createdAt;
    }

    @Override
    public UUID getId() { return id; }
    public PaymentStatus getStatus() { return status; }
    public String getProviderReference() { return providerReference; }
    public UUID getBookingId() { return bookingId; }
}
