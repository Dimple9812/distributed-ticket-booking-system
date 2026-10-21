package com.ticketbooking.event;

import com.ticketbooking.common.PageResponse;
import com.ticketbooking.event.EventDtos.CreateEventRequest;
import com.ticketbooking.event.EventDtos.EventDetailResponse;
import com.ticketbooking.event.EventDtos.EventResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping("/api/v1/events")
    @Operation(summary = "List events (public, paginated)")
    public PageResponse<EventResponse> list(@RequestParam(required = false) EventCategory category,
                                            @RequestParam(defaultValue = "0") @Min(0) int page,
                                            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return eventService.list(category, PageRequest.of(page, size, Sort.by("id")));
    }

    @GetMapping("/api/v1/events/{eventId}")
    @Operation(summary = "Event details with upcoming screenings (public)")
    public EventDetailResponse get(@PathVariable Long eventId) {
        return eventService.get(eventId);
    }

    @PostMapping("/api/v1/admin/events")
    @ResponseStatus(HttpStatus.CREATED)
    @Tag(name = "Admin")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Create an event (ADMIN)")
    public EventResponse create(@Valid @RequestBody CreateEventRequest request) {
        return eventService.create(request);
    }
}
