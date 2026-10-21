package com.ticketbooking.screening;

import com.ticketbooking.event.Event;
import com.ticketbooking.event.EventRepository;
import com.ticketbooking.exception.ApiException;
import com.ticketbooking.exception.ErrorCode;
import com.ticketbooking.screening.ScreeningDtos.AssignSeatsRequest;
import com.ticketbooking.screening.ScreeningDtos.AssignSeatsResponse;
import com.ticketbooking.screening.ScreeningDtos.CreateScreeningRequest;
import com.ticketbooking.screening.ScreeningDtos.ScreeningResponse;
import com.ticketbooking.screening.ScreeningDtos.SeatAvailability;
import com.ticketbooking.screening.ScreeningDtos.SeatMapResponse;
import com.ticketbooking.seat.ScreeningSeat;
import com.ticketbooking.seat.ScreeningSeatRepository;
import com.ticketbooking.seat.Seat;
import com.ticketbooking.seat.SeatRepository;
import com.ticketbooking.seat.SeatStatus;
import com.ticketbooking.seat.SeatType;
import com.ticketbooking.venue.Venue;
import com.ticketbooking.venue.VenueRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Service
public class ScreeningService {

    private final ScreeningRepository screeningRepository;
    private final EventRepository eventRepository;
    private final VenueRepository venueRepository;
    private final SeatRepository seatRepository;
    private final ScreeningSeatRepository screeningSeatRepository;
    private final Clock clock;

    public ScreeningService(ScreeningRepository screeningRepository, EventRepository eventRepository,
                            VenueRepository venueRepository, SeatRepository seatRepository,
                            ScreeningSeatRepository screeningSeatRepository, Clock clock) {
        this.screeningRepository = screeningRepository;
        this.eventRepository = eventRepository;
        this.venueRepository = venueRepository;
        this.seatRepository = seatRepository;
        this.screeningSeatRepository = screeningSeatRepository;
        this.clock = clock;
    }

    @Transactional
    public ScreeningResponse create(CreateScreeningRequest request) {
        Instant now = clock.instant();
        if (!request.startsAt().isAfter(now)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "startsAt must be in the future");
        }
        Event event = eventRepository.findById(request.eventId())
                .orElseThrow(() -> ApiException.notFound("Event", request.eventId()));
        Venue venue = venueRepository.findById(request.venueId())
                .orElseThrow(() -> ApiException.notFound("Venue", request.venueId()));
        Instant endsAt = request.startsAt().plus(Duration.ofMinutes(event.getDurationMinutes()));
        // Friendly pre-check; the EXCLUDE constraint in PostgreSQL is the real guarantee under concurrency.
        if (screeningRepository.existsOverlapping(venue.getId(), request.startsAt(), endsAt)) {
            throw new ApiException(ErrorCode.SCREENING_OVERLAP,
                    "The venue already has a screening overlapping " + request.startsAt() + " - " + endsAt);
        }
        Screening screening = screeningRepository.saveAndFlush(new Screening(event, venue, request.startsAt(), endsAt, now));
        return toResponse(screening, 0, 0);
    }

    @Transactional
    public AssignSeatsResponse assignSeats(Long screeningId, AssignSeatsRequest request) {
        Instant now = clock.instant();
        Screening screening = screeningRepository.findDetailed(screeningId)
                .orElseThrow(() -> ApiException.notFound("Screening", screeningId));
        if (!screening.isBookableAt(now)) {
            throw new ApiException(ErrorCode.SCREENING_NOT_BOOKABLE, "Seats can only be assigned to a future, scheduled screening");
        }
        Long venueId = screening.getVenue().getId();
        List<Seat> seats;
        if (request.seatIds() == null || request.seatIds().isEmpty()) {
            seats = seatRepository.findByVenueIdOrderByRowLabelAscSeatNumberAsc(venueId);
        } else {
            Set<Long> requested = new LinkedHashSet<>(request.seatIds());
            if (requested.size() != request.seatIds().size()) {
                throw new ApiException(ErrorCode.INVALID_SEAT_SELECTION, "seatIds contains duplicates");
            }
            seats = seatRepository.findByVenueIdAndIdIn(venueId, requested);
            if (seats.size() != requested.size()) {
                Set<Long> found = new HashSet<>(seats.stream().map(Seat::getId).toList());
                Set<Long> missing = new TreeSet<>(requested);
                missing.removeAll(found);
                throw new ApiException(ErrorCode.INVALID_SEAT_SELECTION,
                        "Some seats do not belong to the screening's venue", Map.of("invalidSeatIds", missing));
            }
        }
        if (seats.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_SEAT_SELECTION, "The venue has no seats to assign");
        }
        Set<Long> alreadyAssigned = new HashSet<>(screeningSeatRepository.findAssignedSeatIds(screeningId));
        Set<Long> duplicates = new TreeSet<>();
        for (Seat seat : seats) {
            if (alreadyAssigned.contains(seat.getId())) {
                duplicates.add(seat.getId());
            }
        }
        if (!duplicates.isEmpty()) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "Some seats are already assigned to this screening",
                    Map.of("seatIds", duplicates));
        }
        Map<SeatType, BigDecimal> prices = request.priceBySeatType();
        Set<SeatType> unpriced = new TreeSet<>();
        for (Seat seat : seats) {
            if (!prices.containsKey(seat.getSeatType())) {
                unpriced.add(seat.getSeatType());
            }
        }
        if (!unpriced.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Missing price for seat types " + unpriced,
                    Map.of("missingSeatTypes", unpriced));
        }
        List<ScreeningSeat> rows = seats.stream()
                .map(seat -> ScreeningSeat.available(screeningId, seat, prices.get(seat.getSeatType()), now))
                .toList();
        // A concurrent duplicate assignment hits uq_screening_seats and is reported as 409 by the handler.
        screeningSeatRepository.saveAllAndFlush(rows);
        return new AssignSeatsResponse(screeningId, rows.size(), (int) screeningSeatRepository.countByScreeningId(screeningId));
    }

    @Transactional(readOnly = true)
    public ScreeningResponse get(Long screeningId) {
        Screening screening = screeningRepository.findDetailed(screeningId)
                .orElseThrow(() -> ApiException.notFound("Screening", screeningId));
        long total = screeningSeatRepository.countByScreeningId(screeningId);
        long available = screeningSeatRepository.countAvailable(screeningId, clock.instant());
        return toResponse(screening, total, available);
    }

    @Transactional(readOnly = true)
    public SeatMapResponse seatMap(Long screeningId) {
        if (!screeningRepository.existsById(screeningId)) {
            throw ApiException.notFound("Screening", screeningId);
        }
        Instant now = clock.instant();
        List<SeatAvailability> seats = screeningSeatRepository.findAllForScreening(screeningId).stream()
                .map(ss -> new SeatAvailability(ss.getId(), ss.getSeat().getId(), ss.getSeat().getRowLabel(),
                        ss.getSeat().getSeatNumber(), ss.getSeat().getSeatType(), ss.getPrice(), ss.effectiveStatus(now)))
                .toList();
        Map<SeatStatus, Long> summary = new EnumMap<>(SeatStatus.class);
        for (SeatStatus status : SeatStatus.values()) {
            summary.put(status, 0L);
        }
        seats.forEach(s -> summary.merge(s.status(), 1L, Long::sum));
        return new SeatMapResponse(screeningId, summary, seats);
    }

    private static ScreeningResponse toResponse(Screening s, long total, long available) {
        return new ScreeningResponse(s.getId(), s.getEvent().getId(), s.getEvent().getTitle(), s.getVenue().getId(),
                s.getVenue().getName(), s.getVenue().getCity(), s.getStartsAt(), s.getEndsAt(), s.getStatus(),
                total, available);
    }
}
