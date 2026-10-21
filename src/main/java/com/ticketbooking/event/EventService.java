package com.ticketbooking.event;

import com.ticketbooking.common.PageResponse;
import com.ticketbooking.event.EventDtos.CreateEventRequest;
import com.ticketbooking.event.EventDtos.EventDetailResponse;
import com.ticketbooking.event.EventDtos.EventResponse;
import com.ticketbooking.event.EventDtos.ScreeningSummary;
import com.ticketbooking.exception.ApiException;
import com.ticketbooking.screening.ScreeningRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class EventService {

    private final EventRepository eventRepository;
    private final ScreeningRepository screeningRepository;
    private final Clock clock;

    public EventService(EventRepository eventRepository, ScreeningRepository screeningRepository, Clock clock) {
        this.eventRepository = eventRepository;
        this.screeningRepository = screeningRepository;
        this.clock = clock;
    }

    @Transactional
    public EventResponse create(CreateEventRequest request) {
        Event event = eventRepository.save(new Event(request.title().trim(), request.description(), request.category(),
                request.language(), request.durationMinutes(), clock.instant()));
        return EventResponse.from(event);
    }

    @Transactional(readOnly = true)
    public PageResponse<EventResponse> list(EventCategory category, Pageable pageable) {
        Page<Event> page = category == null
                ? eventRepository.findAll(pageable)
                : eventRepository.findByCategory(category, pageable);
        return PageResponse.from(page, EventResponse::from);
    }

    @Transactional(readOnly = true)
    public EventDetailResponse get(Long eventId) {
        Event event = eventRepository.findById(eventId).orElseThrow(() -> ApiException.notFound("Event", eventId));
        var screenings = screeningRepository.findUpcomingForEvent(eventId, clock.instant()).stream()
                .map(s -> new ScreeningSummary(s.getId(), s.getStartsAt(), s.getEndsAt(), s.getVenue().getId(),
                        s.getVenue().getName(), s.getVenue().getCity()))
                .toList();
        return new EventDetailResponse(EventResponse.from(event), screenings);
    }
}
