package com.ticketbooking.outbox;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ticketbooking.common.BookingMetrics;
import com.ticketbooking.config.OutboxProperties;
import com.ticketbooking.support.AbstractIntegrationTest;
import com.ticketbooking.support.ConcurrentRunner;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Transactional outbox -> real Kafka broker -> notification consumer, including broker
 * outages, publisher crashes, duplicate deliveries and poison messages.
 */
class OutboxKafkaIT extends AbstractIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    OutboxPublisher publisher;
    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    OutboxProperties outboxProperties;
    @Autowired
    BookingMetrics metrics;

    @Test
    void confirmedBookingIsPublishedAndProducesExactlyOneNotificationPerChannel() {
        Customer c = registerCustomer("Notify");
        String bookingId = bookSeat(c);
        assertThat(outboxStatus(bookingId)).isEqualTo("PENDING");   // nothing is sent inside the request

        assertThat(publisher.publishPending()).isEqualTo(1);

        assertThat(outboxStatus(bookingId)).isEqualTo("PUBLISHED");
        awaitNotifications(c.id(), 1);
        assertThat(count("select count(*) from notifications where user_id = ? and channel = 'EMAIL'", c.id())).isEqualTo(1);
        assertThat(emailSender.sent()).anySatisfy(s -> assertThat(s).startsWith(c.email() + "|Booking confirmed"));
        assertThat(api.get("/api/v1/notifications", c.token()).body().path("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    void outboxEventsSurviveAKafkaOutageAndArePublishedWhenTheBrokerIsBack() {
        Customer c = registerCustomer("Outage");
        String bookingId = bookSeat(c);

        // A publisher whose broker is unreachable (real Kafka client, dead bootstrap address).
        OutboxPublisher brokenPublisher = new OutboxPublisher(jdbc, deadBrokerTemplate(), outboxProperties, metrics, clock);
        assertThat(brokenPublisher.publishPending()).isZero();

        Map<String, Object> row = jdbc.queryForMap(
                "select status, attempts, last_error, next_attempt_at, claimed_by from outbox_events where aggregate_id = ?", bookingId);
        assertThat(row.get("status")).isEqualTo("PENDING");          // not lost, not marked published
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat((String) row.get("last_error")).isNotBlank();
        assertThat(row.get("claimed_by")).isNull();                  // claim released for the next attempt
        assertThat(((Timestamp) row.get("next_attempt_at")).toInstant()).isAfter(clock.instant());

        // Back-off is respected: an immediate retry does nothing.
        assertThat(publisher.publishPending()).isZero();
        // After the back-off, the healthy publisher delivers it.
        clock.advance(Duration.ofSeconds(5));
        assertThat(publisher.publishPending()).isEqualTo(1);
        assertThat(outboxStatus(bookingId)).isEqualTo("PUBLISHED");
        awaitNotifications(c.id(), 1);
    }

    @Test
    void eventsClaimedByACrashedPublisherAreRecoveredAfterTheClaimLeaseExpires() {
        Customer c = registerCustomer("Crash");
        String bookingId = bookSeat(c);
        // Another instance claimed the row and died before publishing.
        jdbc.update("update outbox_events set claimed_by = 'dead-instance', claimed_until = ? where aggregate_id = ?",
                Timestamp.from(clock.instant().plus(Duration.ofSeconds(30))), bookingId);

        assertThat(publisher.publishPending()).isZero();             // lease still valid: not stolen
        clock.advance(Duration.ofSeconds(31));
        assertThat(publisher.publishPending()).isEqualTo(1);         // lease expired: recovered
        assertThat(outboxStatus(bookingId)).isEqualTo("PUBLISHED");
        awaitNotifications(c.id(), 1);
    }

    @Test
    void concurrentPublishersNeverClaimTheSameEvent() throws Exception {
        List<Customer> customers = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Customer c = registerCustomer("p" + i);
            customers.add(c);
            bookSeat(c);
        }
        List<OutboxPublisher> publishers = List.of(publisher,
                new OutboxPublisher(jdbc, kafkaTemplate, outboxProperties, metrics, clock),
                new OutboxPublisher(jdbc, kafkaTemplate, outboxProperties, metrics, clock));

        List<Integer> published = ConcurrentRunner.runAtOnce(3, i -> publishers.get(i).publishPending());

        assertThat(published.stream().mapToInt(Integer::intValue).sum()).isEqualTo(12);   // each event exactly once
        assertThat(count("select count(*) from outbox_events where status = 'PUBLISHED'")).isEqualTo(12);
        customers.forEach(c -> awaitNotifications(c.id(), 1));
    }

    @Test
    void duplicateKafkaDeliveriesDoNotCreateDuplicateEffects() {
        Customer c = registerCustomer("Dup");
        String bookingId = bookSeat(c);
        String payload = jdbc.queryForObject("select payload::text from outbox_events where aggregate_id = ?", String.class, bookingId);

        // At-least-once in action: the same event arrives three times (e.g. publisher crashed after
        // the broker ack but before marking the row, then republished).
        for (int i = 0; i < 3; i++) {
            kafkaTemplate.send("booking-events", bookingId, payload).join();
        }

        awaitNotifications(c.id(), 1);
        String eventId = jdbc.queryForObject("select id::text from outbox_events where aggregate_id = ?", String.class, bookingId);
        await().atMost(WAIT).untilAsserted(() -> assertThat(count(
                "select count(*) from processed_events where event_id = ?::uuid", eventId)).isEqualTo(1));
        // Give the consumer a chance to (wrongly) process the remaining copies, then assert no duplicates.
        kafkaTemplate.send("booking-events", bookingId, probeEvent(c)).join();   // same key = same partition, after the duplicates
        await().atMost(WAIT).untilAsserted(() -> assertThat(count(
                "select count(*) from notifications where user_id = ? and channel = 'IN_APP'", c.id())).isEqualTo(2));
        assertThat(count("select count(*) from notifications where event_id = ?::uuid", eventId)).isEqualTo(2); // IN_APP + EMAIL
        assertThat(emailSender.sent().stream().filter(s -> s.contains("Booking confirmed")).count()).isEqualTo(1);
    }

    @Test
    void transientConsumerFailuresAreRetriedWithoutDuplicates() {
        Customer c = registerCustomer("Flaky");
        bookSeat(c);
        emailSender.failNextSends(2);   // fewer failures than the retry budget (3 attempts)

        publisher.publishPending();

        awaitNotifications(c.id(), 1);
        assertThat(count("select count(*) from notifications where user_id = ? and channel = 'EMAIL'", c.id())).isEqualTo(1);
    }

    @Test
    void poisonAndPermanentlyFailingMessagesGoToTheDeadLetterTopic() throws Exception {
        Customer c = registerCustomer("Poison");
        bookSeat(c);
        emailSender.alwaysFailFor(c.email());
        String poisonKey = "poison-" + UUID.randomUUID();

        try (KafkaConsumer<String, String> dltConsumer = dltConsumer()) {
            kafkaTemplate.send("booking-events", poisonKey, "{this is not json").join();
            String bookingId = jdbc.queryForObject("select aggregate_id from outbox_events limit 1", String.class);
            publisher.publishPending();   // valid event, but processing always fails -> retries -> DLT

            List<ConsumerRecord<String, String>> dead = new ArrayList<>();
            await().atMost(WAIT).untilAsserted(() -> {
                dltConsumer.poll(Duration.ofMillis(200)).forEach(dead::add);
                assertThat(dead).extracting(ConsumerRecord::key).contains(poisonKey, bookingId);
            });
            ConsumerRecord<String, String> poison = dead.stream().filter(r -> poisonKey.equals(r.key())).findFirst().orElseThrow();
            assertThat(poison.headers().lastHeader("kafka_dlt-exception-fqcn")).isNotNull();
        }
        // The failing event left no partial effect.
        assertThat(count("select count(*) from notifications where user_id = ?", c.id())).isZero();
        assertThat(count("select count(*) from processed_events")).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private String bookSeat(Customer c) {
        ScreeningFixture s = screeningWithSeats(1);
        String holdId = hold(c, s.screeningId(), s.screeningSeatIds()).body().path("holdId").asText();
        var response = confirm(c, holdId);
        assertThat(response.status()).isEqualTo(201);
        return response.body().path("bookingId").asText();
    }

    private String outboxStatus(String aggregateId) {
        return jdbc.queryForObject("select status from outbox_events where aggregate_id = ?", String.class, aggregateId);
    }

    private void awaitNotifications(Long userId, int expected) {
        await().atMost(WAIT).untilAsserted(() -> assertThat(count(
                "select count(*) from notifications where user_id = ? and channel = 'IN_APP'", userId)).isEqualTo(expected));
    }

    private String probeEvent(Customer c) {
        ObjectNode payload = objectMapper.createObjectNode()
                .put("bookingId", UUID.randomUUID().toString()).put("userId", c.id()).put("userEmail", c.email())
                .put("screeningId", 1).put("eventTitle", "Probe").put("refundAmount", "1.00").put("currency", "INR");
        payload.putArray("seats").add("A1");
        ObjectNode envelope = objectMapper.createObjectNode()
                .put("eventId", UUID.randomUUID().toString()).put("eventType", "BookingCancelled").put("eventVersion", 1)
                .put("occurredAt", clock.instant().toString()).put("aggregateType", "Booking").put("aggregateId", "probe");
        envelope.set("payload", payload);
        return envelope.toString();
    }

    private KafkaTemplate<String, String> deadBrokerTemplate() {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 1000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 2000);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
    }

    private KafkaConsumer<String, String> dltConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, INFRA.kafkaBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of("booking-events.DLT"));
        return consumer;
    }
}
