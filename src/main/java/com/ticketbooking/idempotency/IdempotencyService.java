package com.ticketbooking.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.ticketbooking.common.BookingMetrics;
import com.ticketbooking.config.IdempotencyProperties;
import com.ticketbooking.exception.ApiException;
import com.ticketbooking.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Durable, per-user idempotency for state-changing booking operations.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Key scope is (user, Idempotency-Key). Different users can never collide.</li>
 *   <li>Same key + same request  -> the original response is replayed (status and body).</li>
 *   <li>Same key + different request -> 422 IDEMPOTENCY_KEY_REUSED.</li>
 *   <li>Same key while the first request is still running -> 409 IDEMPOTENCY_REQUEST_IN_PROGRESS.</li>
 *   <li>If the operation fails (business error or crash), the claim is removed, so the client
 *       may retry with the same key. Only successful responses are stored.</li>
 *   <li>Completed records are kept for {@code app.idempotency.retention} (24h), then purged.</li>
 * </ul>
 *
 * <h2>Atomicity</h2>
 * Phase 1 claims the key in its own short transaction (INSERT ... ON CONFLICT DO NOTHING
 * on the unique (user_id, idempotency_key) constraint). Phase 2 runs the business operation
 * AND stores the response in one transaction - so either both the booking change and the
 * "completed" record commit, or neither does. A retry after a timeout therefore either
 * replays the stored response or safely re-executes.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final Pattern KEY_FORMAT = Pattern.compile("^[A-Za-z0-9_-]{8,100}$");

    private final JdbcTemplate jdbc;
    private final ObjectMapper responseMapper;
    private final ObjectMapper canonicalMapper;
    private final TransactionTemplate claimTx;
    private final TransactionTemplate workTx;
    private final IdempotencyProperties properties;
    private final BookingMetrics metrics;
    private final Clock clock;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper objectMapper, PlatformTransactionManager txManager,
                              IdempotencyProperties properties, BookingMetrics metrics, Clock clock) {
        this.jdbc = jdbc;
        this.responseMapper = objectMapper;
        // Canonical JSON (sorted keys) so logically identical payloads hash identically.
        this.canonicalMapper = JsonMapper.builder()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .findAndAddModules()
                .build();
        this.claimTx = new TransactionTemplate(txManager);
        this.claimTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.workTx = new TransactionTemplate(txManager);
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    public <T> IdempotentResult<T> execute(Long userId, String key, String operation, Object requestFingerprint,
                                           Class<T> responseType, int successStatus, Supplier<T> action) {
        validateKey(key);
        String requestHash = hash(operation, requestFingerprint);

        Claim claim = claimTx.execute(status -> claim(userId, key, operation, requestHash));
        if (claim instanceof Claim.Replay replay) {
            metrics.idempotentReplay(operation);
            return new IdempotentResult<>(replay.status(), read(replay.body(), responseType), true);
        }
        UUID recordId = ((Claim.Acquired) claim).recordId();
        try {
            T result = workTx.execute(status -> {
                T value = action.get();
                complete(recordId, successStatus, value);
                return value;
            });
            return new IdempotentResult<>(successStatus, result, false);
        } catch (RuntimeException failure) {
            releaseClaim(recordId);
            throw failure;
        }
    }

    // ------------------------------------------------------------------ phase 1

    private Claim claim(Long userId, String key, String operation, String requestHash) {
        Instant now = clock.instant();
        UUID newId = UUID.randomUUID();
        int inserted = jdbc.update("""
                insert into idempotency_records (id, user_id, idempotency_key, operation, request_hash, status,
                                                 locked_until, created_at, expires_at)
                values (?, ?, ?, ?, ?, 'IN_PROGRESS', ?, ?, ?)
                on conflict (user_id, idempotency_key) do nothing
                """, newId, userId, key, operation, requestHash, ts(now.plus(properties.inProgressLease())), ts(now),
                ts(now.plus(properties.retention())));
        if (inserted == 1) {
            return new Claim.Acquired(newId);
        }

        // Key exists. Lock it so concurrent duplicates are evaluated one at a time.
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, operation, request_hash, status, response_status, response_body, locked_until, expires_at
                  from idempotency_records where user_id = ? and idempotency_key = ? for update
                """, userId, key);
        if (rows.isEmpty()) {
            // Deleted between our insert attempt and the select (claim released). Ask the client to retry.
            throw new ApiException(ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS, "Request with this key is being processed; retry shortly");
        }
        Map<String, Object> row = rows.getFirst();
        UUID existingId = (UUID) row.get("id");
        Instant expiresAt = ((Timestamp) row.get("expires_at")).toInstant();

        if (!expiresAt.isAfter(now)) {
            // Retention elapsed: the old record is meaningless, start fresh with a new claim id.
            jdbc.update("delete from idempotency_records where id = ?", existingId);
            return reinsert(userId, key, operation, requestHash, now);
        }
        if (!operation.equals(row.get("operation")) || !requestHash.equals(row.get("request_hash"))) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used with a different request");
        }
        if ("COMPLETED".equals(row.get("status"))) {
            return new Claim.Replay((Integer) row.get("response_status"), (String) row.get("response_body"));
        }
        Instant lockedUntil = ((Timestamp) row.get("locked_until")).toInstant();
        if (lockedUntil.isAfter(now)) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS,
                    "A request with this Idempotency-Key is already in progress");
        }
        // The previous attempt crashed without completing or releasing its claim. Take it over
        // with a NEW claim id: if the old attempt is somehow still running, its completion
        // (WHERE id = old id) will match nothing and its transaction will roll back.
        log.warn("Taking over stale idempotency claim {} for user {}", existingId, userId);
        jdbc.update("delete from idempotency_records where id = ?", existingId);
        return reinsert(userId, key, operation, requestHash, now);
    }

    private Claim reinsert(Long userId, String key, String operation, String requestHash, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into idempotency_records (id, user_id, idempotency_key, operation, request_hash, status,
                                                 locked_until, created_at, expires_at)
                values (?, ?, ?, ?, ?, 'IN_PROGRESS', ?, ?, ?)
                """, id, userId, key, operation, requestHash, ts(now.plus(properties.inProgressLease())), ts(now),
                ts(now.plus(properties.retention())));
        return new Claim.Acquired(id);
    }

    // ------------------------------------------------------------------ phase 2

    private void complete(UUID recordId, int status, Object response) {
        String body;
        try {
            body = responseMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise response for idempotency record", e);
        }
        int updated = jdbc.update("""
                update idempotency_records
                   set status = 'COMPLETED', response_status = ?, response_body = ?, completed_at = ?
                 where id = ? and status = 'IN_PROGRESS'
                """, status, body, ts(clock.instant()), recordId);
        if (updated != 1) {
            // Our claim was taken over or removed; rolling back keeps exactly one execution effective.
            throw new ApiException(ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS,
                    "Idempotency claim was lost; the operation was rolled back. Retry with the same key.");
        }
    }

    private void releaseClaim(UUID recordId) {
        try {
            claimTx.executeWithoutResult(status ->
                    jdbc.update("delete from idempotency_records where id = ? and status = 'IN_PROGRESS'", recordId));
        } catch (RuntimeException e) {
            // The lease expiry makes the key usable again even if this delete fails.
            log.warn("Could not release idempotency claim {}: {}", recordId, e.getMessage());
        }
    }

    /** Purges records past their retention window. Returns the number deleted. */
    public int purgeExpired() {
        return jdbc.update("delete from idempotency_records where expires_at <= ?", ts(clock.instant()));
    }

    // ------------------------------------------------------------------ helpers

    private static void validateKey(String key) {
        if (key == null || !KEY_FORMAT.matcher(key).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Idempotency-Key must be 8-100 characters of letters, digits, '-' or '_'");
        }
    }

    private String hash(String operation, Object fingerprint) {
        try {
            byte[] canonical = canonicalMapper.writeValueAsBytes(Map.of("op", operation, "request", fingerprint));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not hash request", e);
        }
    }

    private <T> T read(String body, Class<T> type) {
        try {
            return responseMapper.readValue(body.getBytes(StandardCharsets.UTF_8), type);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Stored idempotent response could not be read", e);
        }
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private sealed interface Claim {
        record Acquired(UUID recordId) implements Claim {
        }

        record Replay(int status, String body) implements Claim {
        }
    }

    public record IdempotentResult<T>(int status, T body, boolean replayed) {
    }
}
