package com.ticketbooking.event;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class EventDtos {

    private EventDtos() {
    }

    public record CreateEventRequest(
            @NotBlank @Size(max = 200) @Schema(example = "Interstellar (Re-release)") String title,
            @Size(max = 2000) String description,
            @NotNull EventCategory category,
            @Size(max = 40) @Schema(example = "English") String language,
            @Min(1) @Max(1440) @Schema(example = "169") int durationMinutes) {
    }

    public record EventResponse(Long id, String title, String description, EventCategory category, String language,
                                int durationMinutes) {
        public static EventResponse from(Event e) {
            return new EventResponse(e.getId(), e.getTitle(), e.getDescription(), e.getCategory(), e.getLanguage(),
                    e.getDurationMinutes());
        }
    }

    public record ScreeningSummary(Long screeningId, Instant startsAt, Instant endsAt, Long venueId, String venueName,
                                   String city) {
    }

    public record EventDetailResponse(EventResponse event, List<ScreeningSummary> upcomingScreenings) {
    }
}
