package com.ticketbooking.screening;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ScreeningRepository extends JpaRepository<Screening, Long> {

    @Query("select s from Screening s join fetch s.event join fetch s.venue where s.id = :id")
    Optional<Screening> findDetailed(@Param("id") Long id);

    @Query("select s from Screening s join fetch s.venue where s.event.id = :eventId "
            + "and s.status = com.ticketbooking.screening.ScreeningStatus.SCHEDULED and s.startsAt > :now "
            + "order by s.startsAt")
    List<Screening> findUpcomingForEvent(@Param("eventId") Long eventId, @Param("now") Instant now);

    @Query("select count(s) > 0 from Screening s where s.venue.id = :venueId "
            + "and s.status = com.ticketbooking.screening.ScreeningStatus.SCHEDULED "
            + "and s.startsAt < :endsAt and s.endsAt > :startsAt")
    boolean existsOverlapping(@Param("venueId") Long venueId, @Param("startsAt") Instant startsAt,
                              @Param("endsAt") Instant endsAt);
}
