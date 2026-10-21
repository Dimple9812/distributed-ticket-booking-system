package com.ticketbooking.venue;

import com.ticketbooking.exception.ApiException;
import com.ticketbooking.exception.ErrorCode;
import com.ticketbooking.seat.Seat;
import com.ticketbooking.seat.SeatRepository;
import com.ticketbooking.venue.VenueDtos.CreateVenueRequest;
import com.ticketbooking.venue.VenueDtos.RowLayout;
import com.ticketbooking.venue.VenueDtos.SeatResponse;
import com.ticketbooking.venue.VenueDtos.VenueResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class VenueService {

    private final VenueRepository venueRepository;
    private final SeatRepository seatRepository;
    private final Clock clock;

    public VenueService(VenueRepository venueRepository, SeatRepository seatRepository, Clock clock) {
        this.venueRepository = venueRepository;
        this.seatRepository = seatRepository;
        this.clock = clock;
    }

    @Transactional
    public VenueResponse create(CreateVenueRequest request) {
        Set<String> labels = new HashSet<>();
        for (RowLayout row : request.rows()) {
            if (!labels.add(row.rowLabel())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Duplicate row label " + row.rowLabel(),
                        Map.of("rowLabel", row.rowLabel()));
            }
        }
        String name = request.name().trim();
        String city = request.city().trim();
        if (venueRepository.existsByNameAndCity(name, city)) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "A venue named '" + name + "' already exists in " + city);
        }
        Venue venue = venueRepository.save(new Venue(name, city, request.address().trim(), clock.instant()));
        List<Seat> seats = new ArrayList<>();
        for (RowLayout row : request.rows()) {
            for (int n = 1; n <= row.seatCount(); n++) {
                seats.add(new Seat(venue.getId(), row.rowLabel(), n, row.seatType()));
            }
        }
        List<Seat> saved = seatRepository.saveAll(seats);
        return toResponse(venue, saved);
    }

    @Transactional(readOnly = true)
    public VenueResponse get(Long venueId) {
        Venue venue = venueRepository.findById(venueId).orElseThrow(() -> ApiException.notFound("Venue", venueId));
        return toResponse(venue, seatRepository.findByVenueIdOrderByRowLabelAscSeatNumberAsc(venueId));
    }

    /** All venues with seat counts (no seat detail), newest first. */
    @Transactional(readOnly = true)
    public List<VenueResponse> list() {
        return venueRepository.findAll(org.springframework.data.domain.Sort.by("id").descending()).stream()
                .map(v -> new VenueResponse(v.getId(), v.getName(), v.getCity(), v.getAddress(),
                        (int) seatRepository.countByVenueId(v.getId()), List.of()))
                .toList();
    }

    private static VenueResponse toResponse(Venue venue, List<Seat> seats) {
        return new VenueResponse(venue.getId(), venue.getName(), venue.getCity(), venue.getAddress(), seats.size(),
                seats.stream().map(SeatResponse::from).toList());
    }
}
