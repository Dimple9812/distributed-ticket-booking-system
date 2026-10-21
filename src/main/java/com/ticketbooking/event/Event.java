package com.ticketbooking.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventCategory category;

    private String language;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Event() {
    }

    public Event(String title, String description, EventCategory category, String language, int durationMinutes,
                 Instant createdAt) {
        this.title = title;
        this.description = description;
        this.category = category;
        this.language = language;
        this.durationMinutes = durationMinutes;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public EventCategory getCategory() { return category; }
    public String getLanguage() { return language; }
    public int getDurationMinutes() { return durationMinutes; }
    public Instant getCreatedAt() { return createdAt; }
}
