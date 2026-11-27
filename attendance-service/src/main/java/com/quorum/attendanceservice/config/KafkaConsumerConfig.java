package com.quorum.attendanceservice.config;

import com.quorum.attendanceservice.event.InvalidEventException;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Topics this service owns or dead-letters into, which topics it listens to, and the error
 * handling: one bad message is retried a few times (unless it can never succeed) and then
 * copied to {@code <topic>.DLT} with the failure in its headers, so it never blocks a partition.
 */
@Configuration
public class KafkaConsumerConfig {

    /** Bean name read by the listener's SpEL topic list. */
    public static final String INBOUND_TOPICS_BEAN = "inboundTopics";

    @Bean
    public NewTopic odEventsTopic() {
        return TopicBuilder.name(Topics.OD_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic driveEventsDltTopic() {
        return TopicBuilder.name(Topics.DRIVE_EVENTS_DLT).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic clashEventsDltTopic() {
        return TopicBuilder.name(Topics.CLASH_EVENTS_DLT).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic checkinEventsDltTopic() {
        return TopicBuilder.name(Topics.CHECKIN_EVENTS_DLT).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic importEventsDltTopic() {
        return TopicBuilder.name(Topics.IMPORT_EVENTS_DLT).partitions(3).replicas(1).build();
    }

    /**
     * import-events is subscribed only while the TEMPORARY file-attendance bridge is on.
     * Turn it off (quorum.file-attendance-bridge.enabled=false) once checkin-service exists.
     */
    @Bean(INBOUND_TOPICS_BEAN)
    public String[] inboundTopics(@Value("${quorum.file-attendance-bridge.enabled:true}") boolean fileBridge) {
        List<String> topics = new ArrayList<>(List.of(Topics.DRIVE_EVENTS, Topics.CLASH_EVENTS, Topics.CHECKIN_EVENTS));
        if (fileBridge) {
            topics.add(Topics.IMPORT_EVENTS);
        }
        return topics.toArray(String[]::new);
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaProperties kafkaProperties,
                                                 @Value("${quorum.kafka.retry.interval-ms:1000}") long intervalMs,
                                                 @Value("${quorum.kafka.retry.attempts:2}") long retries) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(dltTemplate(kafkaProperties),
                // partition -1: let Kafka pick, so the DLT may have any number of partitions
                (record, ex) -> new TopicPartition(record.topic() + Topics.DLT_SUFFIX, -1));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(intervalMs, retries));
        // Retrying a message that is malformed cannot help (DeserializationException is already non-retryable)
        handler.addNotRetryableExceptions(InvalidEventException.class);
        return handler;
    }

    /**
     * A dead letter is either the raw bytes that failed to deserialize or the JsonNode
     * the listener could not handle, so the value serializer depends on the type.
     */
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
