package com.ticketbooking.booking;

import com.ticketbooking.booking.BookingDtos.BookingResponse;
import com.ticketbooking.booking.BookingDtos.BookingSummary;
import com.ticketbooking.booking.BookingDtos.ConfirmBookingRequest;
import com.ticketbooking.booking.BookingDtos.ScreeningInfo;
import com.ticketbooking.booking.BookingDtos.SeatLine;
import com.ticketbooking.booking.lock.SeatLockService;
import com.ticketbooking.common.AfterTransaction;
import com.ticketbooking.common.BookingMetrics;
import com.ticketbooking.common.PageResponse;
import com.ticketbooking.config.BookingProperties;
import com.ticketbooking.exception.ApiException;
import com.ticketbooking.exception.ErrorCode;
import com.ticketbooking.messaging.BookingEvents;
import com.ticketbooking.outbox.OutboxWriter;
import com.ticketbooking.payment.PaymentService;
import com.ticketbooking.screening.Screening;
import com.ticketbooking.screening.ScreeningRepository;
import com.ticketbooking.seat.ScreeningSeat;
import com.ticketbooking.seat.ScreeningSeatRepository;
import com.ticketbooking.seat.SeatStatus;
import com.ticketbooking.user.User;
import com.ticketbooking.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final SeatHoldRepository holdRepository;
    private final BookingRepository bookingRepository;
    private final ScreeningRepository screeningRepository;
    private final ScreeningSeatRepository screeningSeatRepository;
    private final UserRepository userRepository;
    private final PaymentService paymentService;
    private final OutboxWriter outboxWriter;
    private final SeatLockService lockService;
    private final BookingProperties properties;
    private final BookingMetrics metrics;
    private final Clock clock;

    public BookingService(SeatHoldRepository holdRepository, BookingRepository bookingRepository,
                          ScreeningRepository screeningRepository, ScreeningSeatRepository screeningSeatRepository,
                          UserRepository userRepository, PaymentService paymentService, OutboxWriter outboxWriter,
                          SeatLockService lockService, BookingProperties properties, BookingMetrics metrics, Clock clock) {
        this.holdRepository = holdRepository;
        this.bookingRepository = bookingRepository;
        this.screeningRepository = screeningRepository;
        this.screeningSeatRepository = screeningSeatRepository;
        this.userRepository = userRepository;
        this.paymentService = paymentService;
        this.outboxWriter = outboxWriter;
        this.lockService = lockService;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Confirms a hold into a booking in ONE PostgreSQL transaction:
     * lock hold -> validate -> charge (simulated) -> insert booking + items ->
     * conditional HELD->BOOKED update -> mark hold CONFIRMED -> insert outbox event.
     * Either all of it commits or none of it does.
     */
    @Transactional
    public BookingResponse confirm(Long userId, ConfirmBookingRequest request) {
        Instant now = clock.instant();
        UUID holdId = request.holdId();

        // Row lock on the hold serialises concurrent confirms / releases / expiry of this hold.
        SeatHold hold = holdRepository.lockOwned(holdId, userId)
                .orElseThrow(() -> ApiException.notFound("Hold", holdId));
        switch (hold.getStatus()) {
            case CONFIRMED -> {
                metrics.confirmation("conflict");
                UUID existing = bookingRepository.findByHoldId(holdId).map(Booking::getId).orElse(null);
                throw new ApiException(ErrorCode.HOLD_NOT_ACTIVE, "This hold has already been confirmed",
                        existing == null ? Map.of() : Map.of("bookingId", existing));
            }
            case RELEASED -> {
                metrics.confirmation("conflict");
                throw new ApiException(ErrorCode.HOLD_NOT_ACTIVE, "This hold was released");
            }
            case EXPIRED -> {
                metrics.confirmation("expired");
                throw new ApiException(ErrorCode.HOLD_EXPIRED, "This hold has expired; please select seats again");
            }
            case ACTIVE -> {
                if (hold.isExpiredAt(now)) {
                    metrics.confirmation("expired");
                    throw new ApiException(ErrorCode.HOLD_EXPIRED, "This hold has expired; please select seats again");
                }
            }
        }

        Screening screening = screeningRepository.findDetailed(hold.getScreeningId())
                .orElseThrow(() -> ApiException.notFound("Screening", hold.getScreeningId()));
        if (!screening.isBookableAt(now)) {
            metrics.confirmation("conflict");
            throw new ApiException(ErrorCode.SCREENING_NOT_BOOKABLE, "This screening is no longer open for booking");
        }

        // Verify the seats are still ours. With the hold ACTIVE and unexpired this always holds,
        // but checking explicitly makes the invariant visible and guards against future changes.
        List<ScreeningSeat> seats = screeningSeatRepository.findWithSeats(hold.getScreeningSeatIds());
        boolean allOurs = seats.size() == hold.getScreeningSeatIds().size() && seats.stream()
                .allMatch(s -> s.getStatus() == SeatStatus.HELD && holdId.equals(s.getHoldId()));
        if (!allOurs) {
            metrics.confirmation("conflict");
            throw new ApiException(ErrorCode.HOLD_NOT_ACTIVE, "The held seats are no longer reserved for this hold");
        }

        BigDecimal total = seats.stream().map(ScreeningSeat::getPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        UUID bookingId = UUID.randomUUID();

        // Never trust the client about payment: the (simulated) provider decides.
        String paymentReference;
        try {
            paymentReference = paymentService.charge(userId, holdId, total, properties.getCurrency(),
                    request.paymentMethodToken());
        } catch (ApiException declined) {
            metrics.confirmation("payment_declined");
            throw declined;
        }

        Booking booking = new Booking(bookingId, userId, hold.getScreeningId(), holdId, total, properties.getCurrency(),
                paymentReference, now);
        seats.stream().sorted(Comparator.comparing(ScreeningSeat::getId))
                .forEach(s -> booking.addItem(s.getId(), s.getPrice()));
        bookingRepository.saveAndFlush(booking);   // ux_booking_items_active_seat is checked here
        paymentService.recordCharge(userId, holdId, bookingId, total, properties.getCurrency(), paymentReference);

        // The authoritative state transition: HELD (by this hold, unexpired) -> BOOKED.
        int booked = screeningSeatRepository.bookHeldSeats(holdId, bookingId, now);
        if (booked != seats.size()) {
            metrics.confirmation("conflict");
            throw new ApiException(ErrorCode.HOLD_NOT_ACTIVE,
                    "Seats changed while confirming; nothing was booked (booked " + booked + " of " + seats.size() + ")");
        }
        SeatHold managedHold = holdRepository.findById(holdId).orElseThrow();
        managedHold.transitionTo(HoldStatus.CONFIRMED, now);

        User user = userRepository.findById(userId).orElseThrow();
        List<String> labels = seats.stream().sorted(Comparator.comparing(ScreeningSeat::getId))
                .map(s -> s.getSeat().label()).toList();
        outboxWriter.append(BookingEvents.AGGREGATE_BOOKING, bookingId.toString(), BookingEvents.BOOKING_CONFIRMED,
                BookingEvents.VERSION_1, new BookingEvents.BookingConfirmedV1(bookingId, holdId, userId, user.getEmail(),
                        screening.getId(), screening.getEvent().getTitle(), screening.getVenue().getName(),
                        screening.getStartsAt(), labels, total, properties.getCurrency()));

        Long screeningId = hold.getScreeningId();
        Set<Long> seatIds = Set.copyOf(hold.getScreeningSeatIds());
        // Seats are BOOKED in PostgreSQL. Turn the hold's Redis locks into "sold" markers that live until the
        // screening ends, so future attempts on these seats are rejected by Redis without reaching the database.
        Duration soldTtl = Duration.between(now, screening.getEndsAt()).plusHours(1);
        String soldToken = SeatLockService.bookedToken(bookingId);
        AfterTransaction.onCommit(() -> lockService.markBooked(screeningId, seatIds, holdId.toString(), soldToken, soldTtl));
        AfterTransaction.onCommit(() -> metrics.confirmation("success"));
        log.info("Booking {} confirmed for hold {} by user {} total={} {}", bookingId, holdId, userId, total,
                properties.getCurrency());
        return toResponse(booking, screening, seats);
    }

    /** Cancels a confirmed booking (allowed until the configured cut-off before the show starts). */
    @Transactional
    public BookingResponse cancel(Long userId, UUID bookingId) {
        Instant now = clock.instant();
        Booking booking = bookingRepository.lockOwned(bookingId, userId)
                .orElseThrow(() -> ApiException.notFound("Booking", bookingId));
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new ApiException(ErrorCode.BOOKING_NOT_CANCELLABLE, "Booking is already " + booking.getStatus());
        }
        Screening screening = screeningRepository.findDetailed(booking.getScreeningId()).orElseThrow();
        Instant cutoff = screening.getStartsAt().minus(properties.getCancellationCutoff());
        if (!now.isBefore(cutoff)) {
            throw new ApiException(ErrorCode.BOOKING_NOT_CANCELLABLE,
                    "Bookings can only be cancelled until " + properties.getCancellationCutoff().toHours()
                            + "h before the screening starts");
        }
        booking.cancel(now);
        bookingRepository.saveAndFlush(booking);
        int released = screeningSeatRepository.releaseBookedSeats(bookingId, now);
        if (released != booking.getItems().size()) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION,
                    "Seat state was inconsistent with the booking; cancellation aborted");
        }
        paymentService.refund(userId, booking.getHoldId(), bookingId, booking.getPaymentReference(),
                booking.getTotalAmount(), booking.getCurrency());

        List<ScreeningSeat> seats = screeningSeatRepository.findWithSeats(
                booking.getItems().stream().map(BookingItem::getScreeningSeatId).toList());
        User user = userRepository.findById(userId).orElseThrow();
        outboxWriter.append(BookingEvents.AGGREGATE_BOOKING, bookingId.toString(), BookingEvents.BOOKING_CANCELLED,
                BookingEvents.VERSION_1, new BookingEvents.BookingCancelledV1(bookingId, userId, user.getEmail(),
                        screening.getId(), screening.getEvent().getTitle(),
                        seats.stream().map(s -> s.getSeat().label()).toList(), booking.getTotalAmount(),
                        booking.getCurrency()));
        AfterTransaction.onCommit(metrics::cancellation);
        // Remove the sold markers so the seats can be held again straight away. If this fails, the hold
        // path detects the stale marker against PostgreSQL and removes it (HoldService#acquireLocks).
        Long screeningId = booking.getScreeningId();
        Set<Long> seatIds = Set.copyOf(booking.getItems().stream().map(BookingItem::getScreeningSeatId).toList());
        String soldToken = SeatLockService.bookedToken(bookingId);
        AfterTransaction.onCommit(() -> lockService.release(screeningId, seatIds, soldToken));
        log.info("Booking {} cancelled by user {}", bookingId, userId);
        // Re-read so the response reflects persisted state after the bulk updates cleared the context.
        Booking reloaded = bookingRepository.findByIdAndUserId(bookingId, userId).orElseThrow();
        return toResponse(reloaded, screening, seats);
    }

    @Transactional(readOnly = true)
    public BookingResponse get(Long userId, UUID bookingId) {
        Booking booking = bookingRepository.findByIdAndUserId(bookingId, userId)
                .orElseThrow(() -> ApiException.notFound("Booking", bookingId));
        Screening screening = screeningRepository.findDetailed(booking.getScreeningId()).orElseThrow();
        List<ScreeningSeat> seats = screeningSeatRepository.findWithSeats(
                booking.getItems().stream().map(BookingItem::getScreeningSeatId).toList());
        return toResponse(booking, screening, seats);
    }

    @Transactional(readOnly = true)
    public PageResponse<BookingSummary> list(Long userId, Pageable pageable) {
        return PageResponse.from(bookingRepository.findByUserId(userId, pageable), b -> new BookingSummary(b.getId(),
                b.getStatus(), b.getScreeningId(), b.getTotalAmount(), b.getCurrency(), b.getItems().size(),
                b.getConfirmedAt(), b.getCancelledAt()));
    }

    static SeatLine toSeatLine(ScreeningSeat s) {
        return new SeatLine(s.getId(), s.getSeat().label(), s.getSeat().getSeatType(), s.getPrice());
    }

    private static BookingResponse toResponse(Booking booking, Screening screening, List<ScreeningSeat> seats) {
        List<SeatLine> lines = seats.stream().sorted(Comparator.comparing(ScreeningSeat::getId))
                .map(BookingService::toSeatLine).toList();
        return new BookingResponse(booking.getId(), booking.getHoldId(), booking.getStatus(),
                new ScreeningInfo(screening.getId(), screening.getEvent().getTitle(), screening.getVenue().getName(),
                        screening.getVenue().getCity(), screening.getStartsAt()),
                lines, booking.getTotalAmount(), booking.getCurrency(), booking.getPaymentReference(),
                booking.getConfirmedAt(), booking.getCancelledAt());
    }
}
