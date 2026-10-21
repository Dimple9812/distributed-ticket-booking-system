package com.ticketbooking.booking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeatHoldRepository extends JpaRepository<SeatHold, UUID> {

    Optional<SeatHold> findByIdAndUserId(UUID id, Long userId);

    /**
     * Row-locks the hold, serialising confirm / release / expiry of the same hold.
     * FOR NO KEY UPDATE (not FOR UPDATE) because we never change the key: it still blocks
     * other writers, but lets rows that reference the hold by foreign key (e.g. a declined
     * payment attempt recorded in a separate transaction) be inserted without self-deadlock.
     */
    @Query(value = "select * from seat_holds where id = :id and user_id = :userId for no key update", nativeQuery = true)
    Optional<SeatHold> lockOwned(@Param("id") UUID id, @Param("userId") Long userId);

    /** Candidates for the expiry worker (oldest first). Not locked; each one is re-checked under lock. */
    @Query(value = "select id from seat_holds where status = 'ACTIVE' and expires_at <= :now "
            + "order by expires_at limit :limit", nativeQuery = true)
    List<UUID> findExpiredCandidates(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * Locks the hold only if it is still ACTIVE and expired. SKIP LOCKED means a second
     * worker instance (or a concurrent confirm holding the row lock) is skipped instead
     * of blocking; the hold is simply picked up again on a later run if still eligible.
     */
    @Query(value = "select id from seat_holds where id = :id and status = 'ACTIVE' and expires_at <= :now "
            + "for update skip locked", nativeQuery = true)
    Optional<UUID> lockIfExpired(@Param("id") UUID id, @Param("now") Instant now);

    /** Conditional ACTIVE -> EXPIRED; returns 0 if anything changed the hold in the meantime. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update seat_holds set status = 'EXPIRED', updated_at = :now, version = version + 1 "
            + "where id = :id and status = 'ACTIVE' and expires_at <= :now", nativeQuery = true)
    int markExpired(@Param("id") UUID id, @Param("now") Instant now);
}
