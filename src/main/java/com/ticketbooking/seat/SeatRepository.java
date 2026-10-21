package com.ticketbooking.seat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByVenueIdOrderByRowLabelAscSeatNumberAsc(Long venueId);

    List<Seat> findByVenueIdAndIdIn(Long venueId, Collection<Long> ids);

    long countByVenueId(Long venueId);
}
