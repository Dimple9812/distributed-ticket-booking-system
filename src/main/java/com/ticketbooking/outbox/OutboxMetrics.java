package com.ticketbooking.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Outbox health as gauges, evaluated on each metrics scrape. Alert on a growing
 * backlog, an old oldest-pending event, or any FAILED (parked) events.
 */
@Component
public class OutboxMetrics {

    public OutboxMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        Gauge.builder("booking.outbox.backlog", jdbc,
                        j -> count(j, "select count(*) from outbox_events where status = 'PENDING'"))
                .description("Outbox events waiting to be published").register(registry);
        Gauge.builder("booking.outbox.failed", jdbc,
                        j -> count(j, "select count(*) from outbox_events where status = 'FAILED'"))
                .description("Outbox events parked after exhausting retries").register(registry);
        Gauge.builder("booking.outbox.oldest.pending.age.seconds", jdbc,
                        j -> count(j, "select coalesce(extract(epoch from (now() - min(created_at))), 0) "
                                + "from outbox_events where status = 'PENDING'"))
                .description("Age of the oldest unpublished outbox event").register(registry);
    }

    private static double count(JdbcTemplate jdbc, String sql) {
        try {
            Number n = jdbc.queryForObject(sql, Number.class);
            return n == null ? 0 : n.doubleValue();
        } catch (RuntimeException e) {
            return Double.NaN;   // database unreachable: report "unknown" rather than a misleading zero
        }
    }
}
