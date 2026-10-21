package com.ticketbooking.messaging;

import com.ticketbooking.common.BookingMetrics;
import com.ticketbooking.config.KafkaTopicProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    @Bean
    public NewTopic bookingEventsTopic(KafkaTopicProperties props) {
        return TopicBuilder.name(props.topics().bookingEvents())
                .partitions(props.partitions()).replicas(props.replicationFactor()).build();
    }

    /** Same partition count as the source topic so a failed record keeps its partition in the DLT. */
    @Bean
    public NewTopic bookingEventsDltTopic(KafkaTopicProperties props) {
        return TopicBuilder.name(props.topics().bookingEventsDlt())
                .partitions(props.partitions()).replicas(props.replicationFactor()).build();
    }

    /**
     * Consumer failure policy (picked up by Spring Boot's listener container factory):
     * <ul>
     *   <li>transient failures (e.g. database briefly unavailable) are retried in-process with
     *       exponential back-off; the offset is not committed while retrying;</li>
     *   <li>after the retries are exhausted - or immediately for {@link EventValidationException}
     *       (poison messages) - the record is published to the dead-letter topic with the original
     *       headers plus exception details, and only then is its offset committed.</li>
     * </ul>
     */
    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template, KafkaTopicProperties props,
                                                BookingMetrics metrics) {
        DeadLetterPublishingRecoverer dlt = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(props.topics().bookingEventsDlt(), record.partition()));
        ExponentialBackOff backOff = new ExponentialBackOff(props.consumer().retryInitialInterval().toMillis(), 2.0);
        backOff.setMaxAttempts(props.consumer().retryAttempts());
        DefaultErrorHandler handler = new DefaultErrorHandler((record, ex) -> {
            metrics.consumerError("dead_lettered");
            log.error("Dead-lettering record topic={} partition={} offset={} key={}: {}", record.topic(),
                    record.partition(), record.offset(), record.key(), ex.getMessage());
            dlt.accept(record, ex);
        }, backOff);
        handler.addNotRetryableExceptions(EventValidationException.class, DeserializationException.class);
        handler.setRetryListeners((record, ex, attempt) -> {
            metrics.consumerError("processing");
            log.warn("Retrying record offset={} (attempt {}): {}", record.offset(), attempt, ex.getMessage());
        });
        return handler;
    }
}
