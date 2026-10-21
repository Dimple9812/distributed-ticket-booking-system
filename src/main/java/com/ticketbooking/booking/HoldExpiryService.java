package com.ticketbooking.booking;

import com.ticketbooking.booking.lock.SeatLockService;
import com.ticketbooking.common.AfterTransaction;
import com.ticketbooking.common.BookingMetrics;
import com.ticketbooking.config.BookingProperties;
import com.ticketbooking.messaging.BookingEvents;
import com.ticketbooking.outbox.OutboxWriter;
import com.ticketbooking.screening.ScreeningRepository;
import com.ticketbooking.seat.ScreeningSeatRepository;
import com.ticketbooking.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Expires holds whose deadline passed and returns their seats to AVAILABLE.
 *
 * <p>Safe with many application instances and with retries:
 * <ul>
 *   <li>Each hold is processed in its own short transaction, so one failure never blocks others
 *       and a failed hold is simply retried on the next run.</li>
 *   <li>{@code FOR UPDATE SKIP LOCKED} prevents two workers from processing the same hold and
 *       never waits on a hold that a customer is confirming right now.</li>
 *   <li>The ACTIVE->EXPIRED update and the seat release are conditional
 *       ({@code WHERE status='ACTIVE' AND expires_at<=now}, {@code WHERE hold_id=:thisHold}).
 *       A stale worker can therefore never release seats that a newer hold now owns.</li>
 *   <li>Redis locks are released after commit with compare-and-delete on the hold's token,
 *       so a newer hold's Redis lock is never deleted. A missing Redis key is never treated
 *       as proof of expiry - only the database deadline is.</li>
 * </ul>
 */
@Service
public class HoldExpiryService {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryService.class);

    private final SeatHoldRepository holdRepository;
    private final ScreeningSeatRepository screeningSeatRepository;
    private final ScreeningRepository screeningRepository;
    private final UserRepository userRepository;
    private final OutboxWriter outboxWriter;
    private final SeatLockService lockService;
    private final BookingProperties properties;
    private final BookingMetrics metrics;
    private final TransactionTemplate tx;
    private final Clock clock;

    public HoldExpiryService(SeatHoldRepository holdRepository, ScreeningSeatRepository screeningSeatRepository,
                             ScreeningRepository screeningRepository, UserRepository userRepository,
                             OutboxWriter outboxWriter, SeatLockService lockService, BookingProperties properties,
                             BookingMetrics metrics, PlatformTransactionManager txManager, Clock clock) {
        this.holdRepository = holdRepository;
        this.screeningSeatRepository = screeningSeatRepository;
        this.screeningRepository = screeningRepository;
        this.userRepository = userRepository;
        this.outboxWriter = outboxWriter;
        this.lockService = lockService;
        this.properties = properties;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /** One worker pass. Returns the number of holds expired. */
    public int expireDueHolds() {
        Instant now = clock.instant();
        List<UUID> candidates = holdRepository.findExpiredCandidates(now, properties.getExpiry().getBatchSize());
        int expired = 0;
        for (UUID holdId : candidates) {
            try {
                if (expireHold(holdId, now)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                // Isolated per hold: logged, counted, retried on the next pass.
                log.error("Failed to expire hold {} (will retry): {}", holdId, e.getMessage(), e);
            }
        }
        if (expired > 0) {
            metrics.holdsReleased("expired", expired);
            log.info("Expired {} hold(s)", expired);
        }
        return expired;
    }

    /**
     * Expires a single hold if - and only if - it is still ACTIVE and past its deadline at {@code now}.
     * @return true if this call expired the hold
     */
    public boolean expireHold(UUID holdId, Instant now) {
        Boolean result = tx.execute(status -> {
            Optional<UUID> locked = holdRepository.lockIfExpired(holdId, now);
            if (locked.isEmpty()) {
                return false;   // confirmed, released, already expired, not yet due, or locked by someone else
            }
            SeatHold hold = holdRepository.findById(holdId).orElseThrow();
            if (holdRepository.markExpired(holdId, now) != 1) {
                return false;
            }
            int released = screeningSeatRepository.releaseHeldSeats(holdId, now);
            var screening = screeningRepository.findDetailed(hold.getScreeningId()).orElseThrow();
            var user = userRepository.findById(hold.getUserId()).orElseThrow();
            List<String> labels = screeningSeatRepository.findWithSeats(hold.getScreeningSeatIds()).stream()
                    .map(s -> s.getSeat().label()).sorted().toList();
            outboxWriter.append(BookingEvents.AGGREGATE_HOLD, holdId.toString(), BookingEvents.BOOKING_EXPIRED,
                    BookingEvents.VERSION_1, new BookingEvents.BookingExpiredV1(holdId, user.getId(), user.getEmail(),
                            screening.getId(), screening.getEvent().getTitle(), labels, hold.getExpiresAt()));

            Long screeningId = hold.getScreeningId();
            Set<Long> seatIds = Set.copyOf(hold.getScreeningSeatIds());
            AfterTransaction.onCommit(() -> lockService.release(screeningId, seatIds, holdId.toString()));
            log.debug("Hold {} expired; {} seat(s) released (others may already belong to a newer hold)", holdId, released);
            return true;
        });
        return Boolean.TRUE.equals(result);
    }
}
