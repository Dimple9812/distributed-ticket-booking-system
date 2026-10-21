package com.ticketbooking.booking;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    /** Ownership is part of the lookup: another customer's id simply "does not exist" (404, no leak). */
    @EntityGraph(attributePaths = "items")
    Optional<Booking> findByIdAndUserId(UUID id, Long userId);

    // No fetch-join here: combining a collection fetch with pagination forces in-memory paging.
    // Items are loaded in batches instead (@BatchSize on Booking.items).
    Page<Booking> findByUserId(Long userId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id and b.userId = :userId")
    Optional<Booking> lockOwned(@Param("id") UUID id, @Param("userId") Long userId);

    Optional<Booking> findByHoldId(UUID holdId);
}
