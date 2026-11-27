package com.quorum.timetableservice.config;

import com.quorum.timetableservice.event.InvalidEventException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Topics this service owns, and the error handling for both consumed topics:
 * a bad message is retried a few times (unless it can never succeed) and then
 * copied to {@code <topic>.DLT}, so one bad message never blocks a partition.
 */
@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic clashEventsTopic() {
        return TopicBuilder.name(Topics.CLASH_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic importEventsDltTopic() {
        return TopicBuilder.name(Topics.IMPORT_EVENTS_DLT).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic driveEventsDltTopic() {
        return TopicBuilder.name(Topics.DRIVE_EVENTS_DLT).partitions(3).replicas(1).build();
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaProperties kafkaProperties,
                                                 @Value("${quorum.kafka.retry.interval-ms:1000}") long intervalMs,
                                                 @Value("${quorum.kafka.retry.attempts:2}") long retries) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(dltTemplate(kafkaProperties),
                // partition -1: let Kafka pick, so the DLT may have any number of partitions
                (record, ex) -> new TopicPartition(record.topic() + Topics.DLT_SUFFIX, -1));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(intervalMs, retries));
        handler.addNotRetryableExceptions(InvalidEventException.class);
        return handler;
    }

    /** A dead letter is either raw bytes that failed to deserialize or a JsonNode the listener rejected. */
    private static KafkaTemplate<Object, Object> dltTemplate(KafkaProperties kafkaProperties) {
        Map<Class<?>, Serializer<?>> byType = new LinkedHashMap<>();
        byType.put(byte[].class, new ByteArraySerializer());
        byType.put(Object.class, new JacksonJsonSerializer<>().noTypeInfo());
        DefaultKafkaProducerFactory<Object, Object> factory = new DefaultKafkaProducerFactory<>(
                kafkaProperties.buildProducerProperties(),
                () -> new DelegatingByTypeSerializer(Map.of(String.class, new StringSerializer())),
                () -> new DelegatingByTypeSerializer(byType, true));
        return new KafkaTemplate<>(factory);
    }
}
