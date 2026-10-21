package com.ticketbooking.outbox;

import com.ticketbooking.common.BookingMetrics;
import com.ticketbooking.config.OutboxProperties;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Relays outbox rows to Kafka with <b>at-least-once</b> semantics.
 *
 * <ol>
 *   <li><b>Claim</b> a batch in a short transaction: {@code UPDATE ... WHERE id IN (SELECT ...
 *       FOR UPDATE SKIP LOCKED)} stamps rows with this instance's id and a claim lease.
 *       Concurrent publishers skip each other's rows; if an instance dies, its lease expires
 *       and another instance re-claims the rows (recovery after restarts).</li>
 *   <li><b>Send</b> each event and block until the broker acknowledges it ({@code acks=all},
 *       idempotent producer).</li>
 *   <li><b>Mark</b> it PUBLISHED only after the ack. On failure, schedule a retry with
 *       exponential back-off; after {@code max-attempts} the row is parked as FAILED (alerted
 *       via the outbox metrics) instead of blocking forever.</li>
 * </ol>
 *
 * <p>Why duplicates are possible: steps 2 and 3 are two different systems and cannot be one
 * atomic action. If the process crashes after Kafka acknowledged but before the row is marked,
 * the event is sent again later. That is why every event carries a stable {@code eventId} and
 * consumers de-duplicate on it. Duplicates are acceptable; losing a committed event is not.
 *
 * <p>Ordering: a row is only claimed when no older PENDING row exists for the same aggregate,
 * so events of one booking are published in commit order (and keyed by aggregate id, they land
 * in the same partition).
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;
    private final BookingMetrics metrics;
    private final Clock clock;
    private final String instanceId;

    public OutboxPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafkaTemplate, OutboxProperties properties,
                           BookingMetrics metrics, Clock clock) {
        this.jdbc = jdbc;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
        this.instanceId = ManagementFactory.getRuntimeMXBean().getName() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Claims and publishes one batch. Returns the number of events published. */
    public int publishPending() {
        List<OutboxRow> batch = claimBatch();
        int published = 0;
        Set<String> blockedAggregates = new HashSet<>();
        for (OutboxRow row : batch) {
            if (blockedAggregates.contains(row.aggregateId())) {
                releaseClaim(row.id());   // keep per-aggregate order: wait for the earlier event
                continue;
            }
            if (publish(row)) {
                published++;
            } else {
                blockedAggregates.add(row.aggregateId());
            }
        }
        return published;
    }

    private List<OutboxRow> claimBatch() {
        Instant now = clock.instant();
        // Single statement = its own transaction (auto-commit); rows are claimed atomically.
        return jdbc.query("""
                update outbox_events o
                   set claimed_by = ?, claimed_until = ?
                 where o.id in (
                       select c.id from outbox_events c
                        where c.status = 'PENDING'
                          and c.next_attempt_at <= ?
                          and (c.claimed_until is null or c.claimed_until < ?)
                          and not exists (select 1 from outbox_events older
                                           where older.aggregate_id = c.aggregate_id
                                             and older.status = 'PENDING'
                                             and older.created_at < c.created_at)
                        order by c.created_at
                        limit ?
                        for update skip locked)
                returning o.id, o.topic, o.aggregate_id, o.event_type, o.event_version, o.payload::text as payload,
                          o.correlation_id, o.attempts, o.created_at
                """,
                (rs, i) -> new OutboxRow((UUID) rs.getObject("id"), rs.getString("topic"), rs.getString("aggregate_id"),
                        rs.getString("event_type"), rs.getInt("event_version"), rs.getString("payload"),
                        rs.getString("correlation_id"), rs.getInt("attempts"), rs.getTimestamp("created_at").toInstant()),
                instanceId, Timestamp.from(now.plus(properties.claimTtl())), Timestamp.from(now), Timestamp.from(now),
                properties.batchSize())
                .stream().sorted((a, b) -> a.createdAt().compareTo(b.createdAt())).toList();
    }

    private boolean publish(OutboxRow row) {
        ProducerRecord<String, String> record = new ProducerRecord<>(row.topic(), row.aggregateId(), row.payload());
        record.headers().add("eventId", row.id().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", row.eventType().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventVersion", String.valueOf(row.eventVersion()).getBytes(StandardCharsets.UTF_8));
        if (row.correlationId() != null) {
            record.headers().add("correlationId", row.correlationId().getBytes(StandardCharsets.UTF_8));
        }
        try {
            kafkaTemplate.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailure(row, "interrupted");
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            recordFailure(row, cause.getClass().getSimpleName() + ": " + cause.getMessage());
            return false;
        }
        int updated = jdbc.update("""
                update outbox_events
                   set status = 'PUBLISHED', published_at = ?, attempts = attempts + 1,
                       claimed_by = null, claimed_until = null, last_error = null
                 where id = ? and claimed_by = ?
                """, Timestamp.from(clock.instant()), row.id(), instanceId);
        if (updated == 0) {
            // Our lease expired and another instance re-claimed it: it may publish again (a duplicate the
            // consumer will ignore). Nothing is lost.
            log.warn("Outbox event {} was re-claimed by another publisher after we sent it", row.id());
        }
        metrics.outboxPublished("success");
        return true;
    }

    private void recordFailure(OutboxRow row, String error) {
        int attempts = row.attempts() + 1;
        boolean exhausted = attempts >= properties.maxAttempts();
        Instant next = clock.instant().plus(backoff(attempts));
        jdbc.update("""
                update outbox_events
                   set attempts = ?, last_error = ?, next_attempt_at = ?, status = ?,
                       claimed_by = null, claimed_until = null
                 where id = ? and claimed_by = ?
                """, attempts, truncate(error), Timestamp.from(next), exhausted ? "FAILED" : "PENDING", row.id(), instanceId);
        metrics.outboxPublished(exhausted ? "parked" : "retry");
        if (exhausted) {
            log.error("Outbox event {} ({}) parked as FAILED after {} attempts: {}", row.id(), row.eventType(), attempts, error);
        } else {
            log.warn("Outbox event {} ({}) publish failed (attempt {}), retrying at {}: {}", row.id(), row.eventType(),
                    attempts, next, error);
        }
    }

    private void releaseClaim(UUID id) {
        jdbc.update("update outbox_events set claimed_by = null, claimed_until = null where id = ? and claimed_by = ?",
                id, instanceId);
    }

    static Duration backoff(int attempts) {
        long seconds = (long) Math.min(MAX_BACKOFF.toSeconds(), Math.pow(2, Math.max(0, attempts - 1)));
        return Duration.ofSeconds(seconds);
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() <= 1000 ? s : s.substring(0, 1000);
    }

    record OutboxRow(UUID id, String topic, String aggregateId, String eventType, int eventVersion, String payload,
                     String correlationId, int attempts, Instant createdAt) {
    }
}
