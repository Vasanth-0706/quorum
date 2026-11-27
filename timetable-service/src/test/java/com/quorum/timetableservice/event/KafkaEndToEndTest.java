package com.quorum.timetableservice.event;

import com.quorum.timetableservice.config.Topics;
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

/** In-JVM Kafka (no Docker): import + drive events in, CLASHES_COMPUTED out via the outbox relay. */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {Topics.IMPORT_EVENTS, Topics.DRIVE_EVENTS, Topics.CLASH_EVENTS,
        Topics.IMPORT_EVENTS_DLT, Topics.DRIVE_EVENTS_DLT})
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.listener.auto-startup=true",
        "quorum.outbox.relay.enabled=true",
        "quorum.outbox.relay-interval=100ms",
        "quorum.kafka.retry.interval-ms=10"
})
class KafkaEndToEndTest {

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
    void eventsInClashesOutAndBadMessagesToTheDlt() throws Exception {
        try (KafkaProducer<String, String> p = new KafkaProducer<>(KafkaTestUtils.producerProps(broker),
                new StringSerializer(), new StringSerializer())) {
            p.send(new ProducerRecord<>(Topics.IMPORT_EVENTS, "2026-ODD", "not json")).get();
            p.send(new ProducerRecord<>(Topics.IMPORT_EVENTS, "2026-ODD", """
                    {"eventId":"t-1","eventType":"TIMETABLE_UPLOADED","uploadId":"u-1","semester":"2026-ODD","part":1,
                     "totalParts":1,"uploadedAt":"2026-11-27T10:00:00",
                     "slots":[{"sectionCode":"S12","courseName":"Cloud Native","dayOfWeek":"MONDAY",
                               "startTime":"10:00","endTime":"10:50","room":"C-301"}],
                     "enrolments":[{"studentId":"9900000001","sectionCode":"S12"}]}""")).get();
            p.send(new ProducerRecord<>(Topics.IMPORT_EVENTS, "7", """
                    {"eventId":"s-1","eventType":"SHORTLIST_UPLOADED","uploadId":"u-2","roundId":7,
                     "studentIds":["9900000001","9900000002"],"uploadedAt":"2026-11-27T10:05:00"}""")).get();
            p.send(new ProducerRecord<>(Topics.DRIVE_EVENTS, "3", """
                    {"eventId":"e-1","eventType":"SESSION_SCHEDULED","schemaVersion":1,"occurredAt":"2026-11-27T10:06:00.000Z",
                     "driveId":3,"roundId":7,"sessionId":40,"date":"2026-12-07","startTime":"09:30:00","endTime":"11:00:00",
                     "room":"Hall","status":"SCHEDULED"}""")).get();
        }

        List<ConsumerRecord<String, String>> clashes = read(Topics.CLASH_EVENTS, 1);
        assertThat(clashes).hasSize(1);
        assertThat(clashes.get(0).key()).isEqualTo("7");
        assertThat(clashes.get(0).headers().toArray()).isEmpty();
        JsonNode e = json.readTree(clashes.get(0).value());
        assertThat(e.get("eventType").asString()).isEqualTo("CLASHES_COMPUTED");
        assertThat(e.get("date").asString()).isEqualTo("2026-12-07");
        assertThat(e.get("clashes").size()).isEqualTo(1);
        assertThat(e.get("clashes").get(0).get("studentId").asString()).isEqualTo("9900000001");

        List<ConsumerRecord<String, String>> dead = read(Topics.IMPORT_EVENTS_DLT, 1);
        assertThat(dead).extracting(ConsumerRecord::value).containsExactly("not json");
    }
}
