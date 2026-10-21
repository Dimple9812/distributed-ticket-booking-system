package com.ticketbooking.idempotency;

import com.ticketbooking.support.AbstractIntegrationTest;
import com.ticketbooking.support.ApiClient;
import com.ticketbooking.support.ConcurrentRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyIT extends AbstractIntegrationTest {

    @Autowired
    IdempotencyService idempotencyService;

    @Test
    void repeatedIdenticalHoldRequestReplaysTheOriginalResponse() {
        ScreeningFixture s = screeningWithSeats(2);
        Customer c = registerCustomer("Retry");
        String key = newKey();

        ApiClient.Response first = hold(c, s.screeningId(), s.screeningSeatIds(), key);
        ApiClient.Response second = hold(c, s.screeningId(), List.of(s.screeningSeatIds().get(1), s.screeningSeatIds().get(0)), key);

        assertThat(first.status()).isEqualTo(201);
        assertThat(first.header("Idempotent-Replayed")).isEqualTo("false");
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(count("select count(*) from seat_holds")).isEqualTo(1);
    }

    @Test
    void repeatedIdenticalConfirmDoesNotCreateADuplicateBooking() {
        ScreeningFixture s = screeningWithSeats(1);
        Customer c = registerCustomer("DoubleClick");
        String holdId = hold(c, s.screeningId(), s.screeningSeatIds()).body().path("holdId").asText();
        String key = newKey();

        ApiClient.Response first = confirm(c, holdId, key);
        ApiClient.Response retry = confirm(c, holdId, key);   // e.g. the client timed out and retried

        assertThat(first.status()).isEqualTo(201);
        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.body().path("bookingId")).isEqualTo(first.body().path("bookingId"));
        assertThat(retry.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(count("select count(*) from bookings")).isEqualTo(1);
        assertThat(count("select count(*) from payment_attempts")).isEqualTo(1);
        assertThat(count("select count(*) from outbox_events where event_type = 'BookingConfirmed'")).isEqualTo(1);
    }

    @Test
    void reusingAKeyWithADifferentPayloadIsRejected() {
        ScreeningFixture s = screeningWithSeats(2);
        Customer c = registerCustomer("Reuser");
        String key = newKey();
        assertThat(hold(c, s.screeningId(), List.of(s.screeningSeatIds().get(0)), key).status()).isEqualTo(201);

        ApiClient.Response reused = hold(c, s.screeningId(), List.of(s.screeningSeatIds().get(1)), key);

        assertThat(reused.status()).isEqualTo(422);
        assertThat(reused.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(seatStatus(s.screeningSeatIds().get(1))).isEqualTo("AVAILABLE");
        assertThat(count("select count(*) from seat_holds")).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithTheSameKeyExecuteExactlyOnce() throws Exception {
        ScreeningFixture s = screeningWithSeats(1);
        Customer c = registerCustomer("Hammer");
        String key = newKey();

        List<ApiClient.Response> responses = ConcurrentRunner.runAtOnce(10,
                i -> hold(c, s.screeningId(), s.screeningSeatIds(), key));

        assertThat(count("select count(*) from seat_holds")).isEqualTo(1);
        // Each response is either the (single) successful result or "still in progress, retry".
        assertThat(responses).allSatisfy(r -> assertThat(r.status()).isIn(201, 409));
        assertThat(responses).filteredOn(r -> r.status() == 409)
                .allSatisfy(r -> assertThat(r.code()).isEqualTo("IDEMPOTENCY_REQUEST_IN_PROGRESS"));
        Set<String> holdIds = responses.stream().filter(r -> r.status() == 201)
                .map(r -> r.body().path("holdId").asText()).collect(Collectors.toSet());
        assertThat(holdIds).hasSize(1);
        // After the dust settles, a retry gets the stored response.
        ApiClient.Response later = hold(c, s.screeningId(), s.screeningSeatIds(), key);
        assertThat(later.status()).isEqualTo(201);
        assertThat(later.body().path("holdId").asText()).isEqualTo(holdIds.iterator().next());
    }

    @Test
    void keysAreScopedPerCustomer() {
        ScreeningFixture s = screeningWithSeats(2);
        Customer a = registerCustomer("A");
        Customer b = registerCustomer("B");
        String sharedKey = "shared-key-123";
        assertThat(hold(a, s.screeningId(), List.of(s.screeningSeatIds().get(0)), sharedKey).status()).isEqualTo(201);
        ApiClient.Response other = hold(b, s.screeningId(), List.of(s.screeningSeatIds().get(1)), sharedKey);
        assertThat(other.status()).isEqualTo(201);
        assertThat(other.header("Idempotent-Replayed")).isEqualTo("false");
    }

    @Test
    void failedRequestsDoNotPoisonTheKey() {
        ScreeningFixture s = screeningWithSeats(1);
        Customer a = registerCustomer("A");
        Customer b = registerCustomer("B");
        String holdA = hold(a, s.screeningId(), s.screeningSeatIds()).body().path("holdId").asText();
        String key = newKey();

        // B's attempt fails with a conflict while A holds the seat...
        assertThat(hold(b, s.screeningId(), s.screeningSeatIds(), key).status()).isEqualTo(409);
        // ...A releases, and B's retry with the SAME key now really executes.
        api.delete("/api/v1/holds/" + holdA, a.token());
        ApiClient.Response retry = hold(b, s.screeningId(), s.screeningSeatIds(), key);
        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.header("Idempotent-Replayed")).isEqualTo("false");
    }

    @Test
    void inProgressClaimBlocksDuplicatesUntilItsLeaseExpiresThenCanBeTakenOver() {
        ScreeningFixture s = screeningWithSeats(1);
        Customer c = registerCustomer("Crash");
        String key = newKey();
        // Obtain a genuine record (and request hash) for this request, then undo its effect...
        String holdId = hold(c, s.screeningId(), s.screeningSeatIds(), key).body().path("holdId").asText();
        api.delete("/api/v1/holds/" + holdId, c.token());
        // ...and turn the record into the state a crashed request leaves behind: IN_PROGRESS, no response.
        jdbc.update("""
                update idempotency_records set status = 'IN_PROGRESS', response_status = null, response_body = null,
                       completed_at = null, locked_until = ? where idempotency_key = ?""",
                Timestamp.from(clock.instant().plus(Duration.ofSeconds(30))), key);

        // While the lease is live, a duplicate is told to back off.
        ApiClient.Response blocked = hold(c, s.screeningId(), s.screeningSeatIds(), key);
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(blocked.code()).isEqualTo("IDEMPOTENCY_REQUEST_IN_PROGRESS");

        // Once the lease has expired, a retry takes over the claim and executes for real.
        clock.advance(Duration.ofSeconds(31));
        ApiClient.Response takeover = hold(c, s.screeningId(), s.screeningSeatIds(), key);
        assertThat(takeover.status()).isEqualTo(201);
        assertThat(takeover.body().path("holdId").asText()).isNotEqualTo(holdId);
        assertThat(count("select count(*) from idempotency_records where idempotency_key = ? and status = 'COMPLETED'", key))
                .isEqualTo(1);
        assertThat(count("select count(*) from seat_holds where status = 'ACTIVE'")).isEqualTo(1);
    }

    @Test
    void recordsExpireAfterTheRetentionWindowAndArePurged() {
        ScreeningFixture s = screeningWithSeats(2, Duration.ofDays(3));
        Customer c = registerCustomer("Patient");
        String key = newKey();
        assertThat(hold(c, s.screeningId(), List.of(s.screeningSeatIds().get(0)), key).status()).isEqualTo(201);

        clock.advance(Duration.ofHours(25));
        Customer later = new Customer(c.id(), c.email(), jwtService.issue(userRepository.findById(c.id()).orElseThrow()).token());
        // Within the 24h retention this payload would be rejected with 422; afterwards the key is fresh.
        ApiClient.Response reuse = hold(later, s.screeningId(), List.of(s.screeningSeatIds().get(1)), key);
        assertThat(reuse.status()).isEqualTo(201);
        assertThat(reuse.header("Idempotent-Replayed")).isEqualTo("false");

        clock.advance(Duration.ofHours(25));
        assertThat(idempotencyService.purgeExpired()).isEqualTo(1);
        assertThat(count("select count(*) from idempotency_records")).isZero();
    }
}
