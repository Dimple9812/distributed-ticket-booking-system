package com.ticketbooking.booking;

import com.ticketbooking.common.AssignedIdEntity;
import com.ticketbooking.exception.ApiException;
import com.ticketbooking.exception.ErrorCode;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Entity
@Table(name = "seat_holds")
public class SeatHold extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "screening_id", nullable = false, updatable = false)
    private Long screeningId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HoldStatus status;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    /** Seats requested by this hold, kept for history even after they are released. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "seat_hold_items", joinColumns = @JoinColumn(name = "hold_id"))
    @Column(name = "screening_seat_id", nullable = false)
    private Set<Long> screeningSeatIds = new TreeSet<>();

    protected SeatHold() {
    }

    public SeatHold(UUID id, Long userId, Long screeningId, Collection<Long> screeningSeatIds, Instant expiresAt, Instant now) {
        this.id = id;
        this.userId = userId;
        this.screeningId = screeningId;
        this.screeningSeatIds = new TreeSet<>(screeningSeatIds);
        this.status = HoldStatus.ACTIVE;
        this.expiresAt = expiresAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    /** Guards every transition out of ACTIVE; illegal transitions are reported, never applied. */
    public void transitionTo(HoldStatus target, Instant now) {
        if (status != HoldStatus.ACTIVE || target == HoldStatus.ACTIVE) {
            throw new ApiException(ErrorCode.HOLD_NOT_ACTIVE, "Hold is " + status + " and cannot become " + target);
        }
        this.status = target;
        this.updatedAt = now;
    }

    @Override
    public UUID getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getScreeningId() { return screeningId; }
    public HoldStatus getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Set<Long> getScreeningSeatIds() { return screeningSeatIds; }
}
