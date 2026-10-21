package com.ticketbooking.booking;

import com.ticketbooking.booking.lock.SeatLockService;
import com.ticketbooking.support.AbstractIntegrationTest;
import com.ticketbooking.support.ApiClient;
import com.ticketbooking.support.ConcurrentRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HoldIT extends AbstractIntegrationTest {

    @Autowired
    HoldExpiryService expiryService;

    @Test
    void availableSeatCanBeHeld() {
        ScreeningFixture s = screeningWithSeats(3);
        Customer asha = registerCustomer("Asha");
        Long seat = s.screeningSeatIds().get(0);

        ApiClient.Response response = hold(asha, s.screeningId(), List.of(seat));

        assertThat(response.status()).isEqualTo(201);
        String holdId = response.body().path("holdId").asText();
        assertThat(response.body().path("status").asText()).isEqualTo("ACTIVE");
        assertThat(response.body().path("totalAmount").decimalValue()).isEqualByComparingTo("250.00");
        assertThat(Instant.parse(response.body().path("expiresAt").asText()))
                .isBetween(clock.instant().plus(Duration.ofMinutes(4)), clock.instant().plus(Duration.ofMinutes(6)));
        assertThat(seatStatus(seat)).isEqualTo("HELD");
        // The Redis lock exists and is owned by the hold (its id is the ownership token).
        assertThat(redis.opsForValue().get(SeatLockService.key(s.screeningId(), seat))).isEqualTo(holdId);
        assertThat(redis.getExpire(SeatLockService.key(s.screeningId(), seat))).isBetween(1L, 300L);
        // Seat map reflects the hold for everyone.
        assertThat(api.get("/api/v1/screenings/" + s.screeningId(), null).body().path("availableSeats").asInt()).isEqualTo(2);
        assertThat(api.get("/api/v1/holds/" + holdId, asha.token()).status()).isEqualTo(200);
    }

    @Test
    void twoCustomersCannotSimultaneouslyHoldTheSameSeat() throws Exception {
        ScreeningFixture s = screeningWithSeats(1);
        Long seat = s.screeningSeatIds().get(0);
        List<Customer> customers = java.util.stream.IntStream.range(0, 20).mapToObj(i -> registerCustomer("racer" + i)).toList();

        List<ApiClient.Response> responses = ConcurrentRunner.runAtOnce(customers.size(),
                i -> hold(customers.get(i), s.screeningId(), List.of(seat)));

        assertThat(responses).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(responses).filteredOn(r -> r.status() == 409)
                .hasSize(customers.size() - 1)
                .allSatisfy(r -> assertThat(r.code()).isEqualTo("SEAT_UNAVAILABLE"));
        assertThat(count("select count(*) from seat_holds where status = 'ACTIVE'")).isEqualTo(1);
        assertThat(count("select count(*) from screening_seats where id = ? and status = 'HELD'", seat)).isEqualTo(1);
        // The winner's Redis lock survived the race (losers never delete a lock they do not own).
        String winner = responses.stream().filter(r -> r.status() == 201).findFirst().orElseThrow()
                .body().path("holdId").asText();
        assertThat(redis.opsForValue().get(SeatLockService.key(s.screeningId(), seat))).isEqualTo(winner);
    }

    @Test
    void multiSeatFailureLeavesNoPartialReservation() {
        ScreeningFixture s = screeningWithSeats(3);
        List<Long> seats = s.screeningSeatIds();
        Customer first = registerCustomer("First");
        Customer second = registerCustomer("Second");
        assertThat(hold(first, s.screeningId(), List.of(seats.get(2))).status()).isEqualTo(201);

        ApiClient.Response conflict = hold(second, s.screeningId(), List.of(seats.get(0), seats.get(1), seats.get(2)));

        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.code()).isEqualTo("SEAT_UNAVAILABLE");
        // Nothing of the failed request survives: seats 0 and 1 are still free, in PostgreSQL and in Redis.
        assertThat(seatStatus(seats.get(0))).isEqualTo("AVAILABLE");
        assertThat(seatStatus(seats.get(1))).isEqualTo("AVAILABLE");
        assertThat(redis.hasKey(SeatLockService.key(s.screeningId(), seats.get(0)))).isFalse();
        assertThat(redis.hasKey(SeatLockService.key(s.screeningId(), seats.get(1)))).isFalse();
        assertThat(count("select count(*) from seat_holds where user_id = ?", second.id())).isZero();
        // ...so the same customer can immediately hold the two free seats.
        assertThat(hold(second, s.screeningId(), List.of(seats.get(0), seats.get(1))).status()).isEqualTo(201);
    }

    @Test
    void multiSeatConflictDetectedOnlyByDatabaseAlsoRollsBackCompletely() {
        // Redis has no lock for seat 2 (e.g. the key was lost in a Redis failover), but PostgreSQL says HELD.
        ScreeningFixture s = screeningWithSeats(3);
        List<Long> seats = s.screeningSeatIds();
        Customer first = registerCustomer("First");
        Customer second = registerCustomer("Second");
        assertThat(hold(first, s.screeningId(), List.of(seats.get(2))).status()).isEqualTo(201);
        redis.delete(SeatLockService.key(s.screeningId(), seats.get(2)));

        ApiClient.Response conflict = hold(second, s.screeningId(), seats);

        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.body().path("details").path("unavailableSeatIds").get(0).asLong()).isEqualTo(seats.get(2));
        assertThat(seatStatus(seats.get(0))).isEqualTo("AVAILABLE");
        assertThat(seatStatus(seats.get(1))).isEqualTo("AVAILABLE");
        // The rolled-back request released the Redis locks it had taken for seats 0 and 1.
        assertThat(redis.hasKey(SeatLockService.key(s.screeningId(), seats.get(0)))).isFalse();
        assertThat(redis.hasKey(SeatLockService.key(s.screeningId(), seats.get(1)))).isFalse();
    }

    @Test
    void invalidScreeningAndSeatSelectionsAreRejected() {
        ScreeningFixture s = screeningWithSeats(2);
        ScreeningFixture other = screeningWithSeats(2);
        Customer c = registerCustomer("Val");

        assertThat(hold(c, 999_999L, List.of(s.screeningSeatIds().get(0))).status()).isEqualTo(404);

        ApiClient.Response foreignSeat = hold(c, s.screeningId(), List.of(other.screeningSeatIds().get(0)));
        assertThat(foreignSeat.status()).isEqualTo(422);
        assertThat(foreignSeat.code()).isEqualTo("INVALID_SEAT_SELECTION");

        ApiClient.Response duplicate = hold(c, s.screeningId(), List.of(s.screeningSeatIds().get(0), s.screeningSeatIds().get(0)));
        assertThat(duplicate.status()).isEqualTo(422);

        ApiClient.Response empty = hold(c, s.screeningId(), List.of());
        assertThat(empty.status()).isEqualTo(400);
        assertThat(empty.code()).isEqualTo("VALIDATION_FAILED");

        ApiClient.Response noKey = api.post("/api/v1/screenings/" + s.screeningId() + "/holds", c.token(),
                Map.of("screeningSeatIds", List.of(s.screeningSeatIds().get(0))));
        assertThat(noKey.status()).isEqualTo(400);
        assertThat(noKey.code()).isEqualTo("IDEMPOTENCY_KEY_MISSING");

        ApiClient.Response badKey = hold(c, s.screeningId(), List.of(s.screeningSeatIds().get(0)), "bad key!");
        assertThat(badKey.status()).isEqualTo(400);

        ApiClient.Response anonymous = api.post("/api/v1/screenings/" + s.screeningId() + "/holds", null,
                Map.of("screeningSeatIds", List.of(1)), Map.of("Idempotency-Key", newKey()));
        assertThat(anonymous.status()).isEqualTo(401);

        assertThat(count("select count(*) from seat_holds")).isZero();
        assertThat(count("select count(*) from screening_seats where status <> 'AVAILABLE'")).isZero();
    }

    @Test
    void holdsOnPastScreeningsAreRejected() {
        ScreeningFixture s = screeningWithSeats(1);
        clock.advance(Duration.ofDays(2));   // the screening (tomorrow) has now started
        Customer c = registerCustomer("Late"); // token issued at the advanced time (tokens live 1h)
        ApiClient.Response response = hold(c, s.screeningId(), s.screeningSeatIds());
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("SCREENING_NOT_BOOKABLE");
    }

    @Test
    void customerCanReleaseOwnHoldButNotSomeoneElses() {
        ScreeningFixture s = screeningWithSeats(2);
        Customer owner = registerCustomer("Owner");
        Customer intruder = registerCustomer("Intruder");
        Long seat = s.screeningSeatIds().get(0);
        String holdId = hold(owner, s.screeningId(), List.of(seat)).body().path("holdId").asText();

        assertThat(api.get("/api/v1/holds/" + holdId, intruder.token()).status()).isEqualTo(404);
        assertThat(api.delete("/api/v1/holds/" + holdId, intruder.token()).status()).isEqualTo(404);
        assertThat(seatStatus(seat)).isEqualTo("HELD");

        assertThat(api.delete("/api/v1/holds/" + holdId, owner.token()).status()).isEqualTo(204);
        assertThat(seatStatus(seat)).isEqualTo("AVAILABLE");
        assertThat(redis.hasKey(SeatLockService.key(s.screeningId(), seat))).isFalse();
        assertThat(api.get("/api/v1/holds/" + holdId, owner.token()).body().path("status").asText()).isEqualTo("RELEASED");
        // Releasing twice is an invalid transition.
        assertThat(api.delete("/api/v1/holds/" + holdId, owner.token()).status()).isEqualTo(409);
        // The intruder can now hold the seat.
        assertThat(hold(intruder, s.screeningId(), List.of(seat)).status()).isEqualTo(201);
    }

    @Test
    void expiredHoldCannotBeConfirmed() {
        ScreeningFixture s = screeningWithSeats(1);
        Customer c = registerCustomer("Slow");
        String holdId = hold(c, s.screeningId(), s.screeningSeatIds()).body().path("holdId").asText();

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        ApiClient.Response response = confirm(c, holdId);

        assertThat(response.status()).isEqualTo(410);
        assertThat(response.code()).isEqualTo("HOLD_EXPIRED");
        assertThat(count("select count(*) from bookings")).isZero();
        assertThat(api.get("/api/v1/holds/" + holdId, c.token()).body().path("status").asText()).isEqualTo("EXPIRED");
        // The public seat map already shows the seat as available, before the worker runs.
        assertThat(api.get("/api/v1/screenings/" + s.screeningId(), null).body().path("availableSeats").asInt()).isEqualTo(1);
    }

    @Test
    void expiredHoldIsReleasedByTheWorkerAndTheSeatCanBeBookedAgain() {
        ScreeningFixture s = screeningWithSeats(1);
        Long seat = s.screeningSeatIds().get(0);
        Customer slow = registerCustomer("Slow");
        Customer fast = registerCustomer("Fast");
        String expiredHold = hold(slow, s.screeningId(), List.of(seat)).body().path("holdId").asText();

        clock.advance(Duration.ofMinutes(6));
        assertThat(expiryService.expireDueHolds()).isEqualTo(1);

        assertThat(seatStatus(seat)).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("select status from seat_holds where id = ?::uuid", String.class, expiredHold))
                .isEqualTo("EXPIRED");
        assertThat(redis.hasKey(SeatLockService.key(s.screeningId(), seat))).isFalse();
        assertThat(count("select count(*) from outbox_events where event_type = 'BookingExpired' and aggregate_id = ?",
                expiredHold)).isEqualTo(1);
        // Running the worker again is a no-op (idempotent).
        assertThat(expiryService.expireDueHolds()).isZero();

        ApiClient.Response newHold = hold(fast, s.screeningId(), List.of(seat));
        assertThat(newHold.status()).isEqualTo(201);
        ApiClient.Response booking = confirm(fast, newHold.body().path("holdId").asText());
        assertThat(booking.status()).isEqualTo(201);
        assertThat(seatStatus(seat)).isEqualTo("BOOKED");
    }

    @Test
    void staleCleanupWorkerCannotReleaseANewerValidHold() {
        ScreeningFixture s = screeningWithSeats(1);
        Long seat = s.screeningSeatIds().get(0);
        Customer a = registerCustomer("Alpha");
        Customer b = registerCustomer("Beta");

        UUID holdA = UUID.fromString(hold(a, s.screeningId(), List.of(seat)).body().path("holdId").asText());
        Instant workerSnapshot = clock.instant().plus(Duration.ofMinutes(6));

        // Hold A expires. Before any worker runs, its Redis TTL lapses and customer B takes the seat
        // directly (the database allows taking over an expired HELD seat).
        clock.advance(Duration.ofMinutes(6));
        redis.delete(SeatLockService.key(s.screeningId(), seat));   // simulate Redis TTL expiry
        ApiClient.Response holdB = hold(b, s.screeningId(), List.of(seat));
        assertThat(holdB.status()).isEqualTo(201);
        String holdBId = holdB.body().path("holdId").asText();

        // A delayed worker that selected hold A earlier now processes it.
        assertThat(expiryService.expireHold(holdA, workerSnapshot)).isTrue();

        // Hold A is EXPIRED, but the seat still belongs to hold B - in PostgreSQL and in Redis.
        assertThat(jdbc.queryForObject("select status from seat_holds where id = ?", String.class, holdA)).isEqualTo("EXPIRED");
        assertThat(seatStatus(seat)).isEqualTo("HELD");
        assertThat(jdbc.queryForObject("select hold_id::text from screening_seats where id = ?", String.class, seat))
                .isEqualTo(holdBId);
        assertThat(redis.opsForValue().get(SeatLockService.key(s.screeningId(), seat))).isEqualTo(holdBId);
        // ...and B can still complete the purchase.
        assertThat(confirm(b, holdBId).status()).isEqualTo(201);
    }

    @Test
    void workerDoesNotExpireHoldsBeforeTheirDeadline() {
        ScreeningFixture s = screeningWithSeats(1);
        Customer c = registerCustomer("Patient");
        hold(c, s.screeningId(), s.screeningSeatIds());
        clock.advance(Duration.ofMinutes(4));
        assertThat(expiryService.expireDueHolds()).isZero();
        assertThat(seatStatus(s.screeningSeatIds().get(0))).isEqualTo("HELD");
    }

    @Test
    void missingRedisKeyIsNotTreatedAsExpiry() {
        ScreeningFixture s = screeningWithSeats(1);
        Long seat = s.screeningSeatIds().get(0);
        Customer owner = registerCustomer("Owner");
        Customer other = registerCustomer("Other");
        hold(owner, s.screeningId(), List.of(seat));
        redis.delete(SeatLockService.key(s.screeningId(), seat));   // Redis lost the key; the hold is still valid

        ApiClient.Response attempt = hold(other, s.screeningId(), List.of(seat));
        assertThat(attempt.status()).isEqualTo(409);
        assertThat(expiryService.expireDueHolds()).isZero();
        assertThat(seatStatus(seat)).isEqualTo("HELD");
    }
}
