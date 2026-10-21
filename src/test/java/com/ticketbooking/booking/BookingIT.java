package com.ticketbooking.booking;

import com.ticketbooking.booking.lock.SeatLockService;
import com.ticketbooking.support.AbstractIntegrationTest;
import com.ticketbooking.support.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingIT extends AbstractIntegrationTest {

    @Test
    void confirmingAHoldCreatesABookingAtomically() {
        ScreeningFixture s = screeningWithSeats(4);
        Customer c = registerCustomer("Buyer");
        List<Long> seats = s.screeningSeatIds().subList(0, 2);
        String holdId = hold(c, s.screeningId(), seats).body().path("holdId").asText();

        ApiClient.Response response = confirm(c, holdId);

        assertThat(response.status()).isEqualTo(201);
        String bookingId = response.body().path("bookingId").asText();
        assertThat(response.body().path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(response.body().path("totalAmount").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(response.body().path("seats").size()).isEqualTo(2);
        assertThat(response.body().path("paymentReference").asText()).startsWith("sim_ch_");

        // Every part of the confirmation committed together.
        seats.forEach(seat -> assertThat(seatStatus(seat)).isEqualTo("BOOKED"));
        assertThat(jdbc.queryForObject("select status from seat_holds where id = ?::uuid", String.class, holdId)).isEqualTo("CONFIRMED");
        assertThat(count("select count(*) from booking_items where booking_id = ?::uuid and active", bookingId)).isEqualTo(2);
        assertThat(count("select count(*) from payment_attempts where booking_id = ?::uuid and status = 'SUCCEEDED'", bookingId)).isEqualTo(1);
        assertThat(count("select count(*) from outbox_events where event_type = 'BookingConfirmed' and aggregate_id = ? "
                + "and status = 'PENDING'", bookingId)).isEqualTo(1);
        // The hold's Redis locks became "sold" markers, so later attempts are rejected without touching PostgreSQL.
        seats.forEach(seat -> assertThat(redis.opsForValue().get(SeatLockService.key(s.screeningId(), seat)))
                .isEqualTo("booked:" + bookingId));

        assertThat(api.get("/api/v1/bookings/" + bookingId, c.token()).body().path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(api.get("/api/v1/bookings", c.token()).body().path("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    void aConfirmedSeatCannotBeBookedAgain() {
        ScreeningFixture s = screeningWithSeats(1);
        Long seat = s.screeningSeatIds().get(0);
        Customer first = registerCustomer("First");
        Customer second = registerCustomer("Second");
        String holdId = hold(first, s.screeningId(), List.of(seat)).body().path("holdId").asText();
        assertThat(confirm(first, holdId).status()).isEqualTo(201);

        ApiClient.Response secondHold = hold(second, s.screeningId(), List.of(seat));
        assertThat(secondHold.status()).isEqualTo(409);
        assertThat(secondHold.code()).isEqualTo("SEAT_UNAVAILABLE");
        // Rejected by the sold marker in Redis, which is valid (PostgreSQL agrees) and therefore kept.
        assertThat(redis.opsForValue().get(SeatLockService.key(s.screeningId(), seat))).startsWith("booked:");

        // Even with Redis completely out of the way, PostgreSQL refuses.
        bookingProperties.getRedisLock().setEnabled(false);
        assertThat(hold(second, s.screeningId(), List.of(seat)).status()).isEqualTo(409);

        // Confirming the same hold again (new idempotency key) is an invalid transition.
        ApiClient.Response again = confirm(first, holdId);
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("HOLD_NOT_ACTIVE");
        assertThat(count("select count(*) from bookings")).isEqualTo(1);
    }

    @Test
    void postgresRejectsASecondActiveBookingOfTheSameSeatEvenIfTheApplicationIsBypassed() {
        ScreeningFixture s = screeningWithSeats(1);
        Customer c = registerCustomer("Owner");
        String holdId = hold(c, s.screeningId(), s.screeningSeatIds()).body().path("holdId").asText();
        String bookingId = confirm(c, holdId).body().path("bookingId").asText();
        Long seat = s.screeningSeatIds().get(0);

        // Simulate a buggy code path writing a second active booking item for the same seat.
        assertThatThrownBy(() -> jdbc.update(
                "insert into booking_items (booking_id, screening_seat_id, price, active) values (?::uuid, ?, 1, true)",
                bookingId, seat)).isInstanceOf(DataIntegrityViolationException.class);
        // And a seat row cannot be BOOKED without a booking, nor HELD without a hold.
        assertThatThrownBy(() -> jdbc.update("update screening_seats set booking_id = null where id = ?", seat))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void declinedPaymentCreatesNoBookingAndKeepsTheHold() {
        ScreeningFixture s = screeningWithSeats(1);
        Customer c = registerCustomer("Broke");
        String holdId = hold(c, s.screeningId(), s.screeningSeatIds()).body().path("holdId").asText();

        ApiClient.Response declined = api.post("/api/v1/bookings/confirm", c.token(),
                Map.of("holdId", holdId, "paymentMethodToken", "pm_card_declined"), Map.of("Idempotency-Key", newKey()));

        assertThat(declined.status()).isEqualTo(402);
        assertThat(declined.code()).isEqualTo("PAYMENT_DECLINED");
        assertThat(count("select count(*) from bookings")).isZero();
        assertThat(seatStatus(s.screeningSeatIds().get(0))).isEqualTo("HELD");
        assertThat(count("select count(*) from outbox_events")).isZero();
        // The decline is auditable even though the booking transaction rolled back.
        assertThat(count("select count(*) from payment_attempts where hold_id = ?::uuid and status = 'DECLINED'", holdId)).isEqualTo(1);

        // A client cannot claim it paid: there is no field for it, only a token the server verifies.
        assertThat(confirm(c, holdId).status()).isEqualTo(201);
    }

    @Test
    void databaseFailureDoesNotLeaveAPartiallyConfirmedBooking() {
        ScreeningFixture s = screeningWithSeats(2);
        Customer c = registerCustomer("Unlucky");
        String holdId = hold(c, s.screeningId(), s.screeningSeatIds()).body().path("holdId").asText();
        String key = newKey();

        // Inject a real PostgreSQL failure at the LAST step of the confirmation transaction (outbox insert),
        // after the booking, items, payment and seat updates have already been executed.
        jdbc.execute("""
                create or replace function fail_outbox() returns trigger as $$
                begin raise exception 'simulated database failure'; end; $$ language plpgsql""");
        jdbc.execute("create trigger fail_outbox before insert on outbox_events for each row execute function fail_outbox()");
        try {
            ApiClient.Response failed = confirm(c, holdId, key);
            assertThat(failed.status()).isEqualTo(500);
        } finally {
            jdbc.execute("drop trigger fail_outbox on outbox_events");
            jdbc.execute("drop function fail_outbox()");
        }

        // Nothing partial survived.
        assertThat(count("select count(*) from bookings")).isZero();
        assertThat(count("select count(*) from booking_items")).isZero();
        assertThat(count("select count(*) from payment_attempts")).isZero();
        assertThat(count("select count(*) from screening_seats where status = 'HELD' and hold_id = ?::uuid", holdId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select status from seat_holds where id = ?::uuid", String.class, holdId)).isEqualTo("ACTIVE");
        assertThat(count("select count(*) from idempotency_records where operation = 'CONFIRM_BOOKING'"))
                .isZero();   // claim released -> retry with the same key is allowed

        // Retrying with the SAME idempotency key now succeeds exactly once.
        ApiClient.Response retry = confirm(c, holdId, key);
        assertThat(retry.status()).isEqualTo(201);
        assertThat(count("select count(*) from bookings")).isEqualTo(1);
    }

    @Test
    void cancellationReleasesSeatsRefundsAndIsTerminal() {
        ScreeningFixture s = screeningWithSeats(2);
        Customer c = registerCustomer("Changer");
        Customer other = registerCustomer("Other");
        String holdId = hold(c, s.screeningId(), s.screeningSeatIds()).body().path("holdId").asText();
        String bookingId = confirm(c, holdId).body().path("bookingId").asText();

        ApiClient.Response cancelled = api.post("/api/v1/bookings/" + bookingId + "/cancel", c.token(), null);

        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.body().path("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.body().path("cancelledAt").isNull()).isFalse();
        s.screeningSeatIds().forEach(seat -> assertThat(seatStatus(seat)).isEqualTo("AVAILABLE"));
        s.screeningSeatIds().forEach(seat -> assertThat(redis.hasKey(SeatLockService.key(s.screeningId(), seat))).isFalse());
        assertThat(count("select count(*) from booking_items where booking_id = ?::uuid and active", bookingId)).isZero();
        assertThat(count("select count(*) from payment_attempts where booking_id = ?::uuid and status = 'REFUNDED'", bookingId)).isEqualTo(1);
        assertThat(count("select count(*) from outbox_events where event_type = 'BookingCancelled'")).isEqualTo(1);

        // Terminal state: no second cancel, and the original hold cannot be re-confirmed.
        ApiClient.Response again = api.post("/api/v1/bookings/" + bookingId + "/cancel", c.token(), null);
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("BOOKING_NOT_CANCELLABLE");
        assertThat(confirm(c, holdId).status()).isEqualTo(409);

        // Released seats are bookable by someone else.
        String otherHold = hold(other, s.screeningId(), s.screeningSeatIds()).body().path("holdId").asText();
        assertThat(confirm(other, otherHold).status()).isEqualTo(201);
    }

    @Test
    void cancellationIsRefusedInsideTheCutoffWindow() {
        ScreeningFixture s = screeningWithSeats(1);   // screening starts in 24h, cut-off is 2h before
        Customer c = registerCustomer("LastMinute");
        String holdId = hold(c, s.screeningId(), s.screeningSeatIds()).body().path("holdId").asText();
        String bookingId = confirm(c, holdId).body().path("bookingId").asText();

        clock.advance(Duration.ofHours(23));
        String freshToken = jwtService.issue(userRepository.findById(c.id()).orElseThrow()).token();
        ApiClient.Response response = api.post("/api/v1/bookings/" + bookingId + "/cancel", freshToken, null);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("BOOKING_NOT_CANCELLABLE");
        assertThat(seatStatus(s.screeningSeatIds().get(0))).isEqualTo("BOOKED");
    }

    @Test
    void customersCannotSeeOrModifyAnotherCustomersBookingsOrHolds() {
        ScreeningFixture s = screeningWithSeats(2);
        Customer owner = registerCustomer("Owner");
        Customer attacker = registerCustomer("Attacker");
        String holdId = hold(owner, s.screeningId(), List.of(s.screeningSeatIds().get(0))).body().path("holdId").asText();

        // Attacker tries to confirm the owner's hold by guessing its id.
        ApiClient.Response stolenConfirm = confirm(attacker, holdId);
        assertThat(stolenConfirm.status()).isEqualTo(404);

        String bookingId = confirm(owner, holdId).body().path("bookingId").asText();

        ApiClient.Response view = api.get("/api/v1/bookings/" + bookingId, attacker.token());
        assertThat(view.status()).isEqualTo(404);
        assertThat(view.body().toString()).doesNotContain(owner.email());
        assertThat(api.post("/api/v1/bookings/" + bookingId + "/cancel", attacker.token(), null).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/bookings", attacker.token()).body().path("totalElements").asInt()).isZero();
        assertThat(jdbc.queryForObject("select status from bookings where id = ?::uuid", String.class, bookingId))
                .isEqualTo("CONFIRMED");
        // Admins have no special read access to customer bookings in this API either.
        assertThat(api.get("/api/v1/bookings/" + bookingId, adminToken()).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/bookings/not-a-uuid", owner.token()).status()).isEqualTo(400);
    }

    @Test
    void aStaleSoldMarkerInRedisCannotMakeAnAvailableSeatUnsellable() {
        ScreeningFixture s = screeningWithSeats(1);
        Long seat = s.screeningSeatIds().get(0);
        Customer first = registerCustomer("First");
        Customer second = registerCustomer("Second");
        String bookingId = confirm(first, hold(first, s.screeningId(), List.of(seat)).body().path("holdId").asText())
                .body().path("bookingId").asText();
        api.post("/api/v1/bookings/" + bookingId + "/cancel", first.token(), null);
        // Simulate a cancellation whose Redis cleanup failed: the sold marker is still there.
        redis.opsForValue().set(SeatLockService.key(s.screeningId(), seat), "booked:" + bookingId);

        ApiClient.Response hold = hold(second, s.screeningId(), List.of(seat));

        assertThat(hold.status()).isEqualTo(201);   // PostgreSQL says AVAILABLE -> stale marker removed
        assertThat(redis.opsForValue().get(SeatLockService.key(s.screeningId(), seat)))
                .isEqualTo(hold.body().path("holdId").asText());
    }
}
