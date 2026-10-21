package com.ticketbooking.booking.lock;

import com.ticketbooking.common.BookingMetrics;
import com.ticketbooking.config.BookingProperties;
import com.ticketbooking.config.BookingProperties.RedisFailureMode;
import com.ticketbooking.exception.ApiException;
import com.ticketbooking.exception.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeatLockServiceTest {

    private static final Duration TTL = Duration.ofMinutes(5);

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private BookingProperties properties;
    private SeatLockService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        properties = new BookingProperties();
        service = new SeatLockService(redis, properties, new BookingMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void acquiresEveryLockInAscendingOrderWithTokenAndTtl() {
        when(values.setIfAbsent(any(), eq("token"), eq(TTL))).thenReturn(true);

        assertThat(service.acquire(7L, List.of(1L, 2L, 3L), "token", TTL)).isEqualTo(SeatLockService.LockResult.ACQUIRED);

        InOrder order = inOrder(values);
        order.verify(values).setIfAbsent("seat-lock:{7}:1", "token", TTL);
        order.verify(values).setIfAbsent("seat-lock:{7}:2", "token", TTL);
        order.verify(values).setIfAbsent("seat-lock:{7}:3", "token", TTL);
    }

    @Test
    @SuppressWarnings("unchecked")
    void conflictReleasesOnlyTheLocksThisCallAcquired() {
        when(values.setIfAbsent("seat-lock:{7}:1", "token", TTL)).thenReturn(true);
        when(values.setIfAbsent("seat-lock:{7}:2", "token", TTL)).thenReturn(false);   // held by someone else

        assertThatThrownBy(() -> service.acquire(7L, List.of(1L, 2L, 3L), "token", TTL))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.SEAT_UNAVAILABLE));

        // Compare-and-delete with OUR token, only for seat 1; seat 3 was never attempted.
        verify(redis).execute(any(RedisScript.class), eq(List.of("seat-lock:{7}:1")), eq("token"));
        verify(values, never()).setIfAbsent(eq("seat-lock:{7}:3"), any(), any(Duration.class));
    }

    @Test
    void redisOutageDegradesToDatabaseOnlyByDefault() {
        when(values.setIfAbsent(any(), any(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThat(service.acquire(7L, List.of(1L), "token", TTL)).isEqualTo(SeatLockService.LockResult.DEGRADED);
    }

    @Test
    void redisOutageFailsClosedWhenConfigured() {
        properties.getRedisLock().setFailureMode(RedisFailureMode.FAIL_CLOSED);
        when(values.setIfAbsent(any(), any(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> service.acquire(7L, List.of(1L), "token", TTL))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.LOCK_SERVICE_UNAVAILABLE));
    }

    @Test
    @SuppressWarnings("unchecked")
    void releaseFailuresAreSwallowedBecauseLocksExpireByTtl() {
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenThrow(new RedisConnectionFailureException("down"));
        assertThat(service.release(7L, List.of(1L), "token")).isZero();
    }

    @Test
    void disabledLocksAreSkipped() {
        properties.getRedisLock().setEnabled(false);
        assertThat(service.acquire(7L, List.of(1L), "token", TTL)).isEqualTo(SeatLockService.LockResult.SKIPPED);
        verify(redis, never()).opsForValue();
    }
}
