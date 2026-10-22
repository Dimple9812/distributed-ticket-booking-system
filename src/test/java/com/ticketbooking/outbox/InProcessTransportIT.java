package com.ticketbooking.outbox;

import com.ticketbooking.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application with Kafka switched off entirely (the Render free-tier setup): the outbox is
 * relayed straight to the notification handler, with the same retry and de-duplication rules.
 */
@TestPropertySource(properties = {
        "app.messaging.transport=in-process",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"})
class InProcessTransportIT extends AbstractIntegrationTest {

    @Autowired
    OutboxPublisher publisher;
    @Autowired
    ApplicationContext context;

    @Test
    void noKafkaBeansExist() {
        assertThat(context.getBeansOfType(KafkaTemplate.class)).isEmpty();
        assertThat(context.getBean(EventTransport.class)).isInstanceOf(com.ticketbooking.notification.InProcessEventTransport.class);
    }

    @Test
    void bookingProducesExactlyOneNotificationWithoutKafka() {
        Customer c = registerCustomer("NoKafka");
        ScreeningFixture s = screeningWithSeats(1);
        String holdId = hold(c, s.screeningId(), s.screeningSeatIds()).body().path("holdId").asText();
        String bookingId = confirm(c, holdId).body().path("bookingId").asText();

        assertThat(publisher.publishPending()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from outbox_events where aggregate_id = ?", String.class, bookingId))
                .isEqualTo("PUBLISHED");
        assertThat(count("select count(*) from notifications where user_id = ? and channel = 'IN_APP'", c.id())).isEqualTo(1);

        // Re-delivering the same event (e.g. crash before marking it published) is a no-op.
        jdbc.update("update outbox_events set status = 'PENDING', published_at = null where aggregate_id = ?", bookingId);
        assertThat(publisher.publishPending()).isEqualTo(1);
        assertThat(count("select count(*) from notifications where user_id = ?", c.id())).isEqualTo(2);   // IN_APP + EMAIL
    }

    @Test
    void handlerFailuresAreRetriedThroughTheOutbox() {
        Customer c = registerCustomer("Flaky");
        ScreeningFixture s = screeningWithSeats(1);
        String holdId = hold(c, s.screeningId(), s.screeningSeatIds()).body().path("holdId").asText();
        String bookingId = confirm(c, holdId).body().path("bookingId").asText();
        emailSender.failNextSends(1);

        assertThat(publisher.publishPending()).isZero();
        assertThat(jdbc.queryForObject("select attempts from outbox_events where aggregate_id = ?", Integer.class, bookingId))
                .isEqualTo(1);
        assertThat(count("select count(*) from notifications")).isZero();   // the failed attempt left nothing behind

        clock.advance(Duration.ofSeconds(5));
        assertThat(publisher.publishPending()).isEqualTo(1);
        assertThat(count("select count(*) from notifications where user_id = ? and channel = 'IN_APP'", c.id())).isEqualTo(1);
    }
}
