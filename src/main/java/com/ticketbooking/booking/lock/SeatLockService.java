package com.ticketbooking.booking.lock;

import com.ticketbooking.common.BookingMetrics;
import com.ticketbooking.config.BookingProperties;
import com.ticketbooking.config.BookingProperties.RedisFailureMode;
import com.ticketbooking.exception.ApiException;
import com.ticketbooking.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Redis seat locks: a fast, distributed <em>contention filter</em> in front of PostgreSQL.
 *
 * <p>Each seat gets a key {@code seat-lock:{screeningId}:{screeningSeatId}} whose value is
 * the hold id (the ownership token). Locks are taken with {@code SET key token NX PX ttl},
 * one seat at a time in ascending seat-id order, and released with an atomic Lua
 * compare-and-delete, so a process can only ever delete a lock it still owns.
 *
 * <p>Redis is <b>not</b> the authority for correctness: a lock can expire early, Redis can
 * fail over and lose keys, or be unavailable. PostgreSQL row locks, conditional updates
 * and the unique index on active booking items enforce the real invariant. Redis only
 * prevents most conflicting requests from ever reaching the database.
 */
@Service
public class SeatLockService {

    private static final Logger log = LoggerFactory.getLogger(SeatLockService.class);

    /** Deletes each key only if it still holds our token; returns how many were deleted. */
    private static final RedisScript<Long> COMPARE_AND_DELETE = new DefaultRedisScript<>("""
            local released = 0
            for i, key in ipairs(KEYS) do
              if redis.call('GET', key) == ARGV[1] then
                redis.call('DEL', key)
                released = released + 1
              end
            end
            return released
            """, Long.class);

    /** For each key still holding ARGV[1], replace the value with ARGV[2] and TTL ARGV[3] ms. */
    private static final RedisScript<Long> COMPARE_AND_SET = new DefaultRedisScript<>("""
            local converted = 0
            for i, key in ipairs(KEYS) do
              if redis.call('GET', key) == ARGV[1] then
                redis.call('SET', key, ARGV[2], 'PX', ARGV[3])
                converted = converted + 1
              end
            end
            return converted
            """, Long.class);

    private final StringRedisTemplate redis;
    private final BookingProperties properties;
    private final BookingMetrics metrics;

    public SeatLockService(StringRedisTemplate redis, BookingProperties properties, BookingMetrics metrics) {
        this.redis = redis;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * Acquires all locks or none. On conflict every lock acquired by this call is
     * released and {@link ErrorCode#SEAT_UNAVAILABLE} is thrown.
     *
     * @param sortedSeatIds seat ids in ascending order (deterministic acquisition order)
     */
    public LockResult acquire(Long screeningId, List<Long> sortedSeatIds, String token, Duration ttl) {
        if (!properties.getRedisLock().isEnabled()) {
            return LockResult.SKIPPED;
        }
        List<Long> acquired = new ArrayList<>(sortedSeatIds.size());
        try {
            for (Long seatId : sortedSeatIds) {
                Boolean ok = redis.opsForValue().setIfAbsent(key(screeningId, seatId), token, ttl);
                if (!Boolean.TRUE.equals(ok)) {
                    String holder = redis.opsForValue().get(key(screeningId, seatId));
                    release(screeningId, acquired, token);
                    metrics.seatConflict("redis");
                    throw new LockConflictException(seatId, holder);
                }
                acquired.add(seatId);
            }
            return LockResult.ACQUIRED;
        } catch (DataAccessException redisFailure) {
            release(screeningId, acquired, token);
            metrics.redisDegraded("acquire");
            if (properties.getRedisLock().getFailureMode() == RedisFailureMode.FAIL_CLOSED) {
                log.error("Redis unavailable and failure-mode=FAIL_CLOSED; rejecting hold: {}", redisFailure.getMessage());
                throw new ApiException(ErrorCode.LOCK_SERVICE_UNAVAILABLE,
                        "Seat reservation is temporarily unavailable; please retry shortly", redisFailure);
            }
            log.warn("Redis unavailable; continuing with PostgreSQL-only seat protection: {}", redisFailure.getMessage());
            return LockResult.DEGRADED;
        }
    }

    /**
     * After a booking commits, converts this hold's locks into "sold" markers
     * ({@code booked:<bookingId>}) that live until the screening ends. Later hold attempts for
     * sold seats are then rejected by Redis alone, without touching PostgreSQL. Only keys still
     * holding {@code holdToken} are converted (atomic compare-and-set).
     */
    public int markBooked(Long screeningId, Collection<Long> seatIds, String holdToken, String bookedToken, Duration ttl) {
        if (seatIds.isEmpty() || !properties.getRedisLock().isEnabled()) {
            return 0;
        }
        try {
            List<String> keys = seatIds.stream().map(id -> key(screeningId, id)).toList();
            Long converted = redis.execute(COMPARE_AND_SET, keys, holdToken, bookedToken, String.valueOf(ttl.toMillis()));
            return converted == null ? 0 : converted.intValue();
        } catch (DataAccessException redisFailure) {
            metrics.redisDegraded("mark_booked");
            log.warn("Could not mark seats as booked in Redis (PostgreSQL still protects them): {}", redisFailure.getMessage());
            return 0;
        }
    }

    /**
     * Removes a lock that PostgreSQL has shown to be stale (e.g. a sold marker left behind
     * after a cancellation whose cleanup failed). Deletes only if the value is unchanged.
     */
    public boolean deleteStale(Long screeningId, Long seatId, String observedValue) {
        if (observedValue == null) {
            return false;
        }
        return release(screeningId, List.of(seatId), observedValue) == 1;
    }

    public static final String SOLD_PREFIX = "booked:";

    public static String bookedToken(java.util.UUID bookingId) {
        return SOLD_PREFIX + bookingId;
    }

    /**
     * Best effort: a failed release is logged, never propagated. The lock expires by
     * its TTL anyway, and correctness never depends on the release succeeding.
     */
    public int release(Long screeningId, Collection<Long> seatIds, String token) {
        if (seatIds.isEmpty() || !properties.getRedisLock().isEnabled()) {
            return 0;
        }
        try {
            List<String> keys = seatIds.stream().map(id -> key(screeningId, id)).toList();
            Long released = redis.execute(COMPARE_AND_DELETE, keys, token);
            return released == null ? 0 : released.intValue();
        } catch (DataAccessException redisFailure) {
            metrics.redisDegraded("release");
            log.warn("Could not release seat locks for token {} (they will expire by TTL): {}", token, redisFailure.getMessage());
            return 0;
        }
    }

    /** The hash tag {screeningId} keeps one screening's keys in the same Redis Cluster slot (needed for the Lua script). */
    public static String key(Long screeningId, Long screeningSeatId) {
        return "seat-lock:{" + screeningId + "}:" + screeningSeatId;
    }

    public enum LockResult {
        ACQUIRED,
        /** Redis was down and failure-mode=DB_ONLY: PostgreSQL alone protects this request. */
        DEGRADED,
        SKIPPED
    }
}
