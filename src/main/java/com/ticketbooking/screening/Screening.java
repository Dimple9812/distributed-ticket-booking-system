package com.ticketbooking.screening;

import com.ticketbooking.event.Event;
import com.ticketbooking.venue.Venue;
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

import java.time.Instant;

@Entity
@Table(name = "screenings")
public class Screening {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScreeningStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Screening() {
    }

    public Screening(Event event, Venue venue, Instant startsAt, Instant endsAt, Instant createdAt) {
        this.event = event;
        this.venue = venue;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.status = ScreeningStatus.SCHEDULED;
        this.createdAt = createdAt;
    }

    /** Seats can only be held/booked for a scheduled screening that has not started. */
    public boolean isBookableAt(Instant now) {
        return status == ScreeningStatus.SCHEDULED && startsAt.isAfter(now);
    }

    public Long getId() { return id; }
    public Event getEvent() { return event; }
    public Venue getVenue() { return venue; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public ScreeningStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
