package com.ticketbooking.booking;

import com.ticketbooking.booking.BookingDtos.CreateHoldRequest;
import com.ticketbooking.booking.BookingDtos.HoldResponse;
import com.ticketbooking.booking.BookingDtos.SeatLine;
import com.ticketbooking.booking.lock.LockConflictException;
import com.ticketbooking.booking.lock.SeatLockService;
import com.ticketbooking.common.AfterTransaction;
import com.ticketbooking.common.BookingMetrics;
import com.ticketbooking.config.BookingProperties;
import com.ticketbooking.exception.ApiException;
import com.ticketbooking.exception.ErrorCode;
import com.ticketbooking.screening.Screening;
import com.ticketbooking.screening.ScreeningRepository;
import com.ticketbooking.seat.ScreeningSeat;
import com.ticketbooking.seat.ScreeningSeatRepository;
import com.ticketbooking.seat.SeatStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Temporary seat holds. The flow for a new hold is:
 * <ol>
 *   <li>validate the screening and the requested seats (cheap reads, no locks);</li>
 *   <li>acquire Redis locks in ascending seat order (fast rejection of obvious conflicts);</li>
 *   <li>row-lock the seats in PostgreSQL ({@code SELECT ... ORDER BY id FOR UPDATE});</li>
 *   <li>insert the hold and flip the seats with one conditional UPDATE whose row count
 *       must equal the number of seats - otherwise the whole transaction rolls back.</li>
 * </ol>
 * Step 4 alone is sufficient for correctness. Steps 2-3 reduce contention and give
 * precise conflict messages.
 */
@Service
public class HoldService {

    private static final Logger log = LoggerFactory.getLogger(HoldService.class);

    private final SeatHoldRepository holdRepository;
    private final ScreeningRepository screeningRepository;
    private final ScreeningSeatRepository screeningSeatRepository;
    private final SeatLockService lockService;
    private final BookingProperties properties;
    private final BookingMetrics metrics;
    private final Clock clock;

    public HoldService(SeatHoldRepository holdRepository, ScreeningRepository screeningRepository,
                       ScreeningSeatRepository screeningSeatRepository, SeatLockService lockService,
                       BookingProperties properties, BookingMetrics metrics, Clock clock) {
        this.holdRepository = holdRepository;
        this.screeningRepository = screeningRepository;
        this.screeningSeatRepository = screeningSeatRepository;
        this.lockService = lockService;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Normalises a seat selection: rejects duplicates / oversize requests and returns ids in ascending order. */
    public List<Long> normalizeSeatIds(Collection<Long> requested) {
        Set<Long> unique = new TreeSet<>(requested);
        if (unique.size() != requested.size()) {
            throw new ApiException(ErrorCode.INVALID_SEAT_SELECTION, "The same seat was requested more than once");
        }
        if (unique.size() > properties.getMaxSeatsPerHold()) {
            throw new ApiException(ErrorCode.INVALID_SEAT_SELECTION,
                    "At most " + properties.getMaxSeatsPerHold() + " seats can be held at once");
        }
        return List.copyOf(unique);
    }

    @Transactional
    public HoldResponse createHold(Long userId, Long screeningId, CreateHoldRequest request) {
        Instant now = clock.instant();
        List<Long> seatIds = normalizeSeatIds(request.screeningSeatIds());

        Screening screening = screeningRepository.findById(screeningId)
                .orElseThrow(() -> ApiException.notFound("Screening", screeningId));
        if (!screening.isBookableAt(now)) {
            metrics.holdAttempt("invalid");
            throw new ApiException(ErrorCode.SCREENING_NOT_BOOKABLE, "This screening is not open for booking");
        }
        List<ScreeningSeat> seats = screeningSeatRepository.findForScreening(screeningId, seatIds);
        if (seats.size() != seatIds.size()) {
            Set<Long> found = seats.stream().map(ScreeningSeat::getId).collect(Collectors.toSet());
            List<Long> invalid = seatIds.stream().filter(id -> !found.contains(id)).toList();
            metrics.holdAttempt("invalid");
            throw new ApiException(ErrorCode.INVALID_SEAT_SELECTION, "Some seats do not exist for this screening",
                    Map.of("invalidSeatIds", invalid));
        }

        UUID holdId = UUID.randomUUID();
        String lockToken = holdId.toString();
        Instant expiresAt = now.plus(properties.getHoldTtl());

        SeatLockService.LockResult lock = acquireLocks(screeningId, seatIds, lockToken);
        // If anything below fails - or the surrounding transaction later rolls back for any
        // reason - give the Redis locks back. Compare-and-delete means only OUR locks go.
        AfterTransaction.onRollback(() -> lockService.release(screeningId, seatIds, lockToken));

        List<ScreeningSeat> locked = screeningSeatRepository.lockForUpdate(screeningId, seatIds);
        List<Long> unavailable = locked.stream()
                .filter(s -> s.effectiveStatus(now) != SeatStatus.AVAILABLE)
                .map(ScreeningSeat::getId)
                .toList();
        if (!unavailable.isEmpty()) {
            metrics.seatConflict("database");
            metrics.holdAttempt("conflict");
            throw new ApiException(ErrorCode.SEAT_UNAVAILABLE, "One or more seats are no longer available",
                    Map.of("unavailableSeatIds", unavailable));
        }

        holdRepository.save(new SeatHold(holdId, userId, screeningId, seatIds, expiresAt, now));
        int updated = screeningSeatRepository.holdSeats(screeningId, seatIds, holdId, expiresAt, now);
        if (updated != seatIds.size()) {
            // Cannot normally happen while we hold the row locks; if it does, roll back everything.
            metrics.holdAttempt("conflict");
            throw new ApiException(ErrorCode.SEAT_UNAVAILABLE, "Seats changed concurrently; no seats were held");
        }
        metrics.holdAttempt("success");
        log.info("Hold {} created by user {} for {} seat(s) on screening {} (redisLock={})",
                holdId, userId, seatIds.size(), screeningId, lock);
        return toResponse(holdId, screeningId, HoldStatus.ACTIVE, expiresAt, seats);
    }

    /**
     * Acquires the Redis locks, healing one specific kind of stale key.
     *
     * <p>A "sold" marker ({@code booked:<bookingId>}) lives until the screening ends. If the
     * cancellation that should have removed it failed to reach Redis, the marker would block a seat
     * PostgreSQL considers available. So on a conflict with a sold marker we read the seat's
     * <em>current</em> booking id from PostgreSQL; if it no longer belongs to that booking, the marker
     * is removed (compare-and-delete) and acquisition is retried once.
     *
     * <p>Locks owned by holds are never "healed": they are short-lived (hold TTL) and a hold that is
     * still being written could look available to a concurrent reader - deleting its lock would
     * weaken the filter for no benefit.
     */
    private SeatLockService.LockResult acquireLocks(Long screeningId, List<Long> seatIds, String token) {
        try {
            return lockService.acquire(screeningId, seatIds, token, properties.getHoldTtl());
        } catch (LockConflictException conflict) {
            String holder = conflict.getCurrentHolder();
            String soldPrefix = SeatLockService.SOLD_PREFIX;
            boolean staleSoldMarker = holder != null && holder.startsWith(soldPrefix)
                    && !holder.substring(soldPrefix.length())
                    .equals(screeningSeatRepository.currentBookingId(conflict.getSeatId()).orElse(""));
            if (!staleSoldMarker || !lockService.deleteStale(screeningId, conflict.getSeatId(), holder)) {
                metrics.holdAttempt("conflict");
                throw conflict;
            }
            log.warn("Removed stale sold marker on seat {} ({}); retrying", conflict.getSeatId(), holder);
            return lockService.acquire(screeningId, seatIds, token, properties.getHoldTtl());
        }
    }

    @Transactional(readOnly = true)
    public HoldResponse get(Long userId, UUID holdId) {
        SeatHold hold = holdRepository.findByIdAndUserId(holdId, userId)
                .orElseThrow(() -> ApiException.notFound("Hold", holdId));
        List<ScreeningSeat> seats = screeningSeatRepository.findWithSeats(hold.getScreeningSeatIds());
        HoldStatus visibleStatus = hold.getStatus() == HoldStatus.ACTIVE && hold.isExpiredAt(clock.instant())
                ? HoldStatus.EXPIRED : hold.getStatus();
        return toResponse(hold.getId(), hold.getScreeningId(), visibleStatus, hold.getExpiresAt(), seats);
    }

    /** Customer gives seats back before expiry. */
    @Transactional
    public void release(Long userId, UUID holdId) {
        Instant now = clock.instant();
        SeatHold hold = holdRepository.lockOwned(holdId, userId)
                .orElseThrow(() -> ApiException.notFound("Hold", holdId));
        hold.transitionTo(HoldStatus.RELEASED, now);
        int released = screeningSeatRepository.releaseHeldSeats(holdId, now);
        metrics.holdsReleased("customer", 1);
        Set<Long> seatIds = new HashSet<>(hold.getScreeningSeatIds());
        Long screeningId = hold.getScreeningId();
        AfterTransaction.onCommit(() -> lockService.release(screeningId, seatIds, holdId.toString()));
        log.info("Hold {} released by user {} ({} seat(s) returned)", holdId, userId, released);
    }

    private HoldResponse toResponse(UUID holdId, Long screeningId, HoldStatus status, Instant expiresAt,
                                   List<ScreeningSeat> seats) {
        List<SeatLine> lines = new ArrayList<>(seats.stream()
                .sorted(Comparator.comparing(ScreeningSeat::getId))
                .map(BookingService::toSeatLine)
                .toList());
        BigDecimal total = lines.stream().map(SeatLine::price).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new HoldResponse(holdId, screeningId, status, expiresAt, lines, total, properties.getCurrency());
    }
}
