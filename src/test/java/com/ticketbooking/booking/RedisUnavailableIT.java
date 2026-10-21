package com.ticketbooking.booking;

import com.ticketbooking.config.BookingProperties.RedisFailureMode;
import com.ticketbooking.support.AbstractIntegrationTest;
import com.ticketbooking.support.ApiClient;
import com.ticketbooking.support.ConcurrentRunner;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.ServerSocket;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the application against a Redis address where nothing is listening,
 * i.e. Redis is completely unavailable for the whole test.
 */
class RedisUnavailableIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void deadRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", RedisUnavailableIT::unusedPort);
        // This class gets its own Spring context; keep only the main context's Kafka consumer active
        // so the two contexts never compete for booking events.
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Test
    void redisReallyIsUnavailableInThisContext() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> redis.opsForValue().get("probe"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void dbOnlyModeKeepsSellingSafelyWithoutRedis() throws Exception {
        bookingProperties.getRedisLock().setFailureMode(RedisFailureMode.DB_ONLY);
        ScreeningFixture s = screeningWithSeats(1);
        Long seat = s.screeningSeatIds().get(0);
        List<Customer> customers = IntStream.range(0, 20).mapToObj(i -> registerCustomer("r" + i)).toList();

        List<Integer> outcomes = ConcurrentRunner.runAtOnce(customers.size(), i -> {
            ApiClient.Response hold = hold(customers.get(i), s.screeningId(), List.of(seat));
            return hold.status() != 201 ? hold.status() : confirm(customers.get(i), hold.body().path("holdId").asText()).status();
        });

        assertThat(outcomes).filteredOn(code -> code == 201).hasSize(1);
        assertThat(outcomes).allSatisfy(code -> assertThat(code).isIn(201, 409));
        assertThat(count("select count(*) from booking_items where screening_seat_id = ? and active", seat)).isEqualTo(1);
    }

    @Test
    void failClosedModeRejectsHoldsGracefully() {
        bookingProperties.getRedisLock().setFailureMode(RedisFailureMode.FAIL_CLOSED);
        ScreeningFixture s = screeningWithSeats(1);
        Customer c = registerCustomer("Closed");

        ApiClient.Response response = hold(c, s.screeningId(), s.screeningSeatIds());

        assertThat(response.status()).isEqualTo(503);
        assertThat(response.code()).isEqualTo("LOCK_SERVICE_UNAVAILABLE");
        assertThat(seatStatus(s.screeningSeatIds().get(0))).isEqualTo("AVAILABLE");
        assertThat(count("select count(*) from seat_holds")).isZero();
        assertThat(count("select count(*) from idempotency_records")).isZero();   // retry with same key allowed later
    }

    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
