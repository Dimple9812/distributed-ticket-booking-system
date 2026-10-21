package com.ticketbooking.booking;

import com.ticketbooking.support.AbstractIntegrationTest;
import com.ticketbooking.support.ApiClient;
import com.ticketbooking.support.ConcurrentRunner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The headline guarantee: no matter how many customers race for a seat, at most one
 * active confirmed booking can exist for it. Requests go through the real HTTP stack
 * into real PostgreSQL and Redis; a start latch makes them genuinely simultaneous.
 */
class ConcurrencyIT extends AbstractIntegrationTest {

    private static final int CUSTOMERS = 50;

    @Test
    void manySimultaneousCustomersCanNeverBookTheSameSeatTwice() throws Exception {
        ScreeningFixture s = screeningWithSeats(1);
        Long seat = s.screeningSeatIds().get(0);
        List<Customer> customers = IntStream.range(0, CUSTOMERS).mapToObj(i -> registerCustomer("c" + i)).toList();

        List<Integer> outcomes = ConcurrentRunner.runAtOnce(CUSTOMERS, i -> holdThenConfirm(customers.get(i), s, seat));

        assertThat(outcomes).filteredOn(code -> code == 201).hasSize(1);
        assertSingleActiveBooking(seat);
    }

    @Test
    void theDatabaseAloneStillPreventsDoubleBookingWhenRedisLocksAreDisabled() throws Exception {
        bookingProperties.getRedisLock().setEnabled(false);   // simulate locks that expired / were lost
        ScreeningFixture s = screeningWithSeats(1);
        Long seat = s.screeningSeatIds().get(0);
        List<Customer> customers = IntStream.range(0, CUSTOMERS).mapToObj(i -> registerCustomer("n" + i)).toList();

        List<Integer> outcomes = ConcurrentRunner.runAtOnce(CUSTOMERS, i -> holdThenConfirm(customers.get(i), s, seat));

        assertThat(outcomes).filteredOn(code -> code == 201).hasSize(1);
        assertThat(outcomes).allSatisfy(code -> assertThat(code).isIn(201, 409));
        assertSingleActiveBooking(seat);
    }

    @Test
    void concurrentConfirmationsOfOneHoldProduceExactlyOneBooking() throws Exception {
        ScreeningFixture s = screeningWithSeats(2);
        Customer c = registerCustomer("Spammer");
        String holdId = hold(c, s.screeningId(), s.screeningSeatIds()).body().path("holdId").asText();

        // Different idempotency keys, so idempotency does not help: the hold row lock and the
        // unique constraint on bookings.hold_id must.
        List<ApiClient.Response> responses = ConcurrentRunner.runAtOnce(20, i -> confirm(c, holdId));

        assertThat(responses).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(responses).filteredOn(r -> r.status() != 201)
                .allSatisfy(r -> assertThat(r.code()).isEqualTo("HOLD_NOT_ACTIVE"));
        assertThat(count("select count(*) from bookings")).isEqualTo(1);
        assertThat(count("select count(*) from payment_attempts where status = 'SUCCEEDED'")).isEqualTo(1);
    }

    @Test
    void overlappingMultiSeatRequestsNeitherDeadlockNorPartiallySucceed() throws Exception {
        // Pairs like [1,2] vs [2,3] vs [3,1] are the classic deadlock shape if locks are taken in request order.
        ScreeningFixture s = screeningWithSeats(3);
        List<Long> ids = s.screeningSeatIds();
        List<List<Long>> requests = List.of(List.of(ids.get(1), ids.get(0)), List.of(ids.get(2), ids.get(1)),
                List.of(ids.get(0), ids.get(2)));
        bookingProperties.getRedisLock().setEnabled(false);   // force all contention onto PostgreSQL row locks
        List<Customer> customers = IntStream.range(0, 30).mapToObj(i -> registerCustomer("o" + i)).toList();

        List<ApiClient.Response> responses = ConcurrentRunner.runAtOnce(customers.size(),
                i -> hold(customers.get(i), s.screeningId(), requests.get(i % 3)));

        assertThat(responses).allSatisfy(r -> assertThat(r.status()).isIn(201, 409));
        long successes = responses.stream().filter(r -> r.status() == 201).count();
        assertThat(successes).isEqualTo(1);   // any two requests overlap, so exactly one can win
        // Exactly the winner's two seats are held; nothing partial from the losers.
        assertThat(count("select count(*) from screening_seats where status = 'HELD'")).isEqualTo(2);
        assertThat(count("select count(*) from seat_holds")).isEqualTo(1);
    }

    private int holdThenConfirm(Customer customer, ScreeningFixture s, Long seat) {
        ApiClient.Response hold = hold(customer, s.screeningId(), List.of(seat));
        if (hold.status() != 201) {
            return hold.status();
        }
        return confirm(customer, hold.body().path("holdId").asText()).status();
    }

    private void assertSingleActiveBooking(Long seat) {
        assertThat(count("select count(*) from booking_items where screening_seat_id = ? and active", seat)).isEqualTo(1);
        assertThat(count("select count(*) from bookings where status = 'CONFIRMED'")).isEqualTo(1);
        assertThat(seatStatus(seat)).isEqualTo("BOOKED");
    }
}
