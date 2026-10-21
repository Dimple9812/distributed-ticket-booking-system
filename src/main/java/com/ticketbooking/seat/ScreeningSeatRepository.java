package com.ticketbooking.seat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ScreeningSeatRepository extends JpaRepository<ScreeningSeat, Long> {

    @Query("select ss from ScreeningSeat ss join fetch ss.seat s where ss.screeningId = :screeningId "
            + "order by s.rowLabel, s.seatNumber")
    List<ScreeningSeat> findAllForScreening(@Param("screeningId") Long screeningId);

    @Query("select ss from ScreeningSeat ss join fetch ss.seat where ss.screeningId = :screeningId and ss.id in :ids")
    List<ScreeningSeat> findForScreening(@Param("screeningId") Long screeningId, @Param("ids") Collection<Long> ids);

    @Query("select ss from ScreeningSeat ss join fetch ss.seat where ss.id in :ids")
    List<ScreeningSeat> findWithSeats(@Param("ids") Collection<Long> ids);

    long countByScreeningId(Long screeningId);

    @Query(value = "select count(*) from screening_seats where screening_id = :screeningId "
            + "and (status = 'AVAILABLE' or (status = 'HELD' and held_until <= :now))", nativeQuery = true)
    long countAvailable(@Param("screeningId") Long screeningId, @Param("now") Instant now);

    @Query(value = "select seat_id from screening_seats where screening_id = :screeningId", nativeQuery = true)
    List<Long> findAssignedSeatIds(@Param("screeningId") Long screeningId);

    /** Fresh read of the seat's current booking (bypasses the persistence context). */
    @Query(value = "select cast(booking_id as varchar) from screening_seats where id = :id and booking_id is not null",
            nativeQuery = true)
    java.util.Optional<String> currentBookingId(@Param("id") Long id);

    /**
     * Row-locks the requested seats in ascending id order. A consistent lock order
     * means two transactions locking overlapping seat sets can never deadlock.
     */
    @Query(value = "select * from screening_seats where screening_id = :screeningId and id in (:ids) "
            + "order by id for update", nativeQuery = true)
    List<ScreeningSeat> lockForUpdate(@Param("screeningId") Long screeningId, @Param("ids") Collection<Long> ids);

    /**
     * AVAILABLE (or HELD-but-expired) -> HELD. Callers require the returned row
     * count to equal the number of requested seats, otherwise they roll back:
     * a multi-seat hold is all-or-nothing.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update screening_seats
               set status = 'HELD', hold_id = :holdId, held_until = :heldUntil,
                   version = version + 1, updated_at = :now
             where screening_id = :screeningId
               and id in (:ids)
               and (status = 'AVAILABLE' or (status = 'HELD' and held_until <= :now))
            """, nativeQuery = true)
    int holdSeats(@Param("screeningId") Long screeningId, @Param("ids") Collection<Long> ids,
                  @Param("holdId") UUID holdId, @Param("heldUntil") Instant heldUntil, @Param("now") Instant now);

    /** HELD by this hold and not yet expired -> BOOKED. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update screening_seats
               set status = 'BOOKED', booking_id = :bookingId, hold_id = null, held_until = null,
                   version = version + 1, updated_at = :now
             where hold_id = :holdId
               and status = 'HELD'
               and held_until > :now
            """, nativeQuery = true)
    int bookHeldSeats(@Param("holdId") UUID holdId, @Param("bookingId") UUID bookingId, @Param("now") Instant now);

    /**
     * HELD by THIS hold -> AVAILABLE. Seats that were taken over by a newer hold
     * no longer carry this hold id, so a stale release can never touch them.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update screening_seats
               set status = 'AVAILABLE', hold_id = null, held_until = null,
                   version = version + 1, updated_at = :now
             where hold_id = :holdId
               and status = 'HELD'
            """, nativeQuery = true)
    int releaseHeldSeats(@Param("holdId") UUID holdId, @Param("now") Instant now);

    /** BOOKED by this booking -> AVAILABLE (cancellation). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update screening_seats
               set status = 'AVAILABLE', booking_id = null,
                   version = version + 1, updated_at = :now
             where booking_id = :bookingId
               and status = 'BOOKED'
            """, nativeQuery = true)
    int releaseBookedSeats(@Param("bookingId") UUID bookingId, @Param("now") Instant now);
}
