package com.quorum.attendanceservice.event;

import com.quorum.attendanceservice.config.Topics;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * In-JVM Kafka (no Docker): sessions, clashes and checkin-service marks in, OD_GRANTED out on
 * od-events through the outbox relay, and bad messages to their topic's DLT without blocking it.
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {Topics.DRIVE_EVENTS, Topics.CLASH_EVENTS, Topics.CHECKIN_EVENTS,
        Topics.IMPORT_EVENTS, Topics.OD_EVENTS, Topics.DRIVE_EVENTS_DLT, Topics.CLASH_EVENTS_DLT,
        Topics.CHECKIN_EVENTS_DLT, Topics.IMPORT_EVENTS_DLT})
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:attendance-kafka-od;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.listener.auto-startup=true",
        "quorum.outbox.relay.enabled=true",
        "quorum.outbox.relay-interval=100ms",
        "quorum.kafka.retry.interval-ms=10"
})
class OdKafkaEndToEndTest {

    @Autowired
    EmbeddedKafkaBroker broker;
    @Autowired
    ObjectMapper json;

    private List<ConsumerRecord<String, String>> read(String topic, int atLeast) {
        Map<String, Object> props = KafkaTestUtils.consumerProps(broker, "reader-" + topic, false);
        props.put("auto.offset.reset", "earliest");
        try (Consumer<String, String> c = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(),
                new StringDeserializer()).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(c, topic);
            List<ConsumerRecord<String, String>> out = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 20_000;
            while (out.size() < atLeast && System.currentTimeMillis() < deadline) {
                KafkaTestUtils.getRecords(c, Duration.ofMillis(500)).forEach(out::add);
            }
            return out;
        }
    }

    @Test
    void eventsInOdGrantedOutAndBadMessagesToTheDlt() throws Exception {
        try (KafkaProducer<String, String> p = new KafkaProducer<>(KafkaTestUtils.producerProps(broker),
                new StringSerializer(), new StringSerializer())) {
            p.send(new ProducerRecord<>(Topics.CLASH_EVENTS, "7", "not json")).get();
            p.send(new ProducerRecord<>(Topics.CLASH_EVENTS, "7", """
                    {"eventId":"bad-1","eventType":"CLASHES_COMPUTED","date":"2026-12-07","clashes":[]}""")).get();
            p.send(new ProducerRecord<>(Topics.CHECKIN_EVENTS, "41", """
                    {"eventId":"bad-2","eventType":"ATTENDANCE_MARKED","sessionId":41,"studentId":"12345",
                     "present":true,"source":"RFID","markedBy":"r","markedAt":"2026-12-07T04:00:00Z","markId":"m"}""")).get();
            p.send(new ProducerRecord<>(Topics.DRIVE_EVENTS, "3", """
                    {"eventId":"d-1","eventType":"SESSION_SCHEDULED","schemaVersion":1,"occurredAt":"2026-11-27T10:00:00.000Z",
                     "driveId":3,"roundId":7,"sessionId":41,"date":"2026-12-07","startTime":"09:30:00","endTime":"10:15:00",
                     "room":"Hall","status":"SCHEDULED"}""")).get();
            p.send(new ProducerRecord<>(Topics.CLASH_EVENTS, "7", """
                    {"eventId":"c-1","eventType":"CLASHES_COMPUTED","schemaVersion":1,"occurredAt":"2026-11-27T10:01:00.000Z",
                     "roundId":7,"date":"2026-12-07","windowStart":"09:30:00","windowEnd":"10:15:00","semester":"2026-ODD",
                     "clashes":[{"studentId":"9900000001","sectionCode":"S12","courseName":"Cloud Native",
                                 "dayOfWeek":"MONDAY","slotStart":"10:00:00","slotEnd":"10:50:00","room":"C-301"}]}""")).get();
            // a file upload as checkin-service publishes it, then the same mark again under a new
            // eventId (e.g. its outbox resent it): stored once, so OD is granted once
            for (String eventId : List.of("m-1", "m-1-again")) {
                p.send(new ProducerRecord<>(Topics.CHECKIN_EVENTS, "41", """
                        {"eventId":"%s","eventType":"ATTENDANCE_MARKED","schemaVersion":1,
                         "occurredAt":"2026-12-07T06:18:00.000Z","sessionId":41,"roundId":7,"studentId":"9900000001",
                         "present":true,"source":"FILE","markedBy":"import-service",
                         "markedAt":"2026-12-07T06:18:00.000Z","markId":"6f1c1f0e-3f7c-3b8e-9a52-6a1d1c1a0001"}"""
                        .formatted(eventId))).get();
            }
            p.send(new ProducerRecord<>(Topics.CHECKIN_EVENTS, "41", """
                    {"eventId":"m-2","eventType":"ATTENDANCE_MARKED","schemaVersion":1,
                     "occurredAt":"2026-12-07T06:18:00.000Z","sessionId":41,"roundId":7,"studentId":"9900000002",
                     "present":true,"source":"RFID","markedBy":"R-HALL","markedAt":"2026-12-07T04:05:00.000Z",
                     "markId":"6f1c1f0e-3f7c-3b8e-9a52-6a1d1c1a0002"}""")).get();
        }

        List<ConsumerRecord<String, String>> od = read(Topics.OD_EVENTS, 1);
        assertThat(od).hasSize(1);
        assertThat(od.get(0).key()).isEqualTo("9900000001");
        assertThat(od.get(0).headers().toArray()).isEmpty();
        JsonNode e = json.readTree(od.get(0).value());
        assertThat(e.get("eventType").asString()).isEqualTo("OD_GRANTED");
        assertThat(e.get("studentId").asString()).isEqualTo("9900000001");
        assertThat(e.get("date").asString()).isEqualTo("2026-12-07");
        assertThat(e.get("sectionCode").asString()).isEqualTo("S12");
        assertThat(e.get("courseName").asString()).isEqualTo("Cloud Native");
        assertThat(e.get("slotStart").asString()).isEqualTo("10:00:00");
        assertThat(e.get("slotEnd").asString()).isEqualTo("10:50:00");
        assertThat(e.get("sessionId").asLong()).isEqualTo(41);
        assertThat(e.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(e.get("eventId").asString()).hasSize(36);

        List<ConsumerRecord<String, String>> deadClashes = read(Topics.CLASH_EVENTS_DLT, 2);
        assertThat(deadClashes).extracting(ConsumerRecord::value).hasSize(2).first().isEqualTo("not json");
        assertThat(json.readTree(deadClashes.get(1).value()).get("eventId").asString()).isEqualTo("bad-1");
        assertThat(deadClashes.get(1).headers().lastHeader("kafka_dlt-exception-message")).isNotNull();
        assertThat(deadClashes).allSatisfy(r -> assertThat(r.key()).isEqualTo("7"));

        List<ConsumerRecord<String, String>> deadMarks = read(Topics.CHECKIN_EVENTS_DLT, 1);
        assertThat(deadMarks).hasSize(1);
        assertThat(new String(deadMarks.get(0).headers().lastHeader("kafka_dlt-exception-message").value()))
                .contains("10 digits");
    }
}
