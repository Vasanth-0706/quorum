package com.quorum.checkinservice.event;

import com.quorum.checkinservice.MutableClock;
import com.quorum.checkinservice.config.Topics;
import com.quorum.checkinservice.repository.SessionViewRepository;
import com.quorum.checkinservice.service.CardService;
import com.quorum.checkinservice.service.DeviceService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * In-JVM Kafka (no Docker): a session arrives on drive-events, a card is tapped, and the
 * ATTENDANCE_MARKED that the outbox relay sends is read by a consumer configured exactly like
 * attendance-service's (ErrorHandlingDeserializer → JacksonJsonDeserializer to JsonNode, no type
 * headers) and checked the way attendance-service checks it. A file upload on import-events gives
 * a FILE mark, and a message that is not JSON goes to import-events.DLT without blocking it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MutableClock.Config.class)
@EmbeddedKafka(partitions = 1, topics = {Topics.DRIVE_EVENTS, Topics.IMPORT_EVENTS, Topics.CHECKIN_EVENTS,
        Topics.DRIVE_EVENTS_DLT, Topics.IMPORT_EVENTS_DLT})
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:checkin-kafka;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.listener.auto-startup=true",
        "quorum.outbox.relay.enabled=true",
        "quorum.outbox.relay-interval=100ms",
        "quorum.kafka.retry.interval-ms=10"
})
class CheckinKafkaEndToEndTest {

    static final String A = "9900000201";
    static final String B = "9900000202";

    @Autowired
    EmbeddedKafkaBroker broker;
    @Autowired
    MockMvc mvc;
    @Autowired
    DeviceService devices;
    @Autowired
    CardService cards;
    @Autowired
    SessionViewRepository sessions;

    /** The consumer settings of attendance-service's application.properties. */
    private Consumer<String, JsonNode> attendanceStyleConsumer() {
        Map<String, Object> props = KafkaTestUtils.consumerProps(broker, "attendance-service", false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JacksonJsonDeserializer.class);
        props.put(JacksonJsonDeserializer.VALUE_DEFAULT_TYPE, JsonNode.class.getName());
        props.put(JacksonJsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        Consumer<String, JsonNode> c = new DefaultKafkaConsumerFactory<String, JsonNode>(props).createConsumer();
        broker.consumeFromAnEmbeddedTopic(c, Topics.CHECKIN_EVENTS);
        return c;
    }

    private static <K, V> List<ConsumerRecord<K, V>> poll(Consumer<K, V> c, int atLeast) {
        List<ConsumerRecord<K, V>> out = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 20_000;
        while (out.size() < atLeast && System.currentTimeMillis() < deadline) {
            KafkaTestUtils.getRecords(c, Duration.ofMillis(500)).forEach(out::add);
        }
        return out;
    }

    /** What attendance-service's MarkService reads from an ATTENDANCE_MARKED, with its rules. */
    private static void assertAttendanceServiceAccepts(JsonNode e) {
        assertThat(e.get("eventType").asString()).isEqualTo("ATTENDANCE_MARKED");
        assertThat(e.get("eventId").asString()).hasSize(36);
        assertThat(e.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(OffsetDateTime.parse(e.get("occurredAt").asString())).isNotNull();
        assertThat(e.get("markId").asString().length()).isBetween(1, 100);
        assertThat(e.get("sessionId").isIntegralNumber()).isTrue();
        assertThat(e.get("roundId").isIntegralNumber()).isTrue();
        assertThat(e.get("studentId").asString()).matches("\\d{10}");
        assertThat(e.get("present").isBoolean()).isTrue();
        assertThat(Set.of("RFID", "MANUAL", "FILE")).contains(e.get("source").asString());
        assertThat(e.get("markedBy").asString().length()).isBetween(1, 64);
        assertThat(OffsetDateTime.parse(e.get("markedAt").asString())).isNotNull();
    }

    @Test
    void tapBecomesAttendanceMarkedThatAnAttendanceStyleConsumerReads() throws Exception {
        try (KafkaProducer<String, String> p = new KafkaProducer<>(KafkaTestUtils.producerProps(broker),
                new StringSerializer(), new StringSerializer())) {
            p.send(new ProducerRecord<>(Topics.IMPORT_EVENTS, "41", "not json")).get();
            p.send(new ProducerRecord<>(Topics.DRIVE_EVENTS, "3", """
                    {"eventId":"%s","eventType":"SESSION_SCHEDULED","schemaVersion":1,"occurredAt":"2026-11-27T10:00:00.000Z",
                     "driveId":3,"roundId":7,"sessionId":41,"date":"2026-12-07","startTime":"09:30:00","endTime":"10:15:00",
                     "room":"Hall A","status":"SCHEDULED"}""".formatted(UUID.randomUUID()))).get();
        }
        long deadline = System.currentTimeMillis() + 20_000;
        while (sessions.findById(41L).isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(sessions.findById(41L)).as("session 41 from drive-events").isPresent();

        String key = devices.register("R-HALL-A", "Hall A", "dir-1").apiKey();
        cards.enrol(A, "04A31B2C", "coord-1");

        mvc.perform(post("/checkins/tap").header("X-Device-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardUid\":\"04a31b2c\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("MARKED"));

        try (KafkaProducer<String, String> p = new KafkaProducer<>(KafkaTestUtils.producerProps(broker),
                new StringSerializer(), new StringSerializer())) {
            p.send(new ProducerRecord<>(Topics.IMPORT_EVENTS, "41", """
                    {"eventId":"a-1","eventType":"ATTENDANCE_UPLOADED","uploadId":"u-1","sessionId":41,
                     "studentIds":["%s","%s"],"uploadedAt":"2026-12-07T11:48:00"}""".formatted(A, B))).get();
        }

        try (Consumer<String, JsonNode> c = attendanceStyleConsumer()) {
            List<ConsumerRecord<String, JsonNode>> records = poll(c, 2);
            assertThat(records).hasSize(2);
            records.forEach(r -> {
                assertThat(r.key()).isEqualTo("41");
                assertThat(r.headers().toArray()).as("no type headers").isEmpty();
                assertAttendanceServiceAccepts(r.value());
            });
            JsonNode rfid = records.get(0).value();
            assertThat(rfid.get("studentId").asString()).isEqualTo(A);
            assertThat(rfid.get("source").asString()).isEqualTo("RFID");
            assertThat(rfid.get("markedBy").asString()).isEqualTo("R-HALL-A");
            assertThat(rfid.get("markedAt").asString()).isEqualTo("2026-12-07T04:15:00.000Z");
            assertThat(rfid.get("sessionId").asLong()).isEqualTo(41);
            assertThat(rfid.get("roundId").asLong()).isEqualTo(7);
            assertThat(rfid.get("present").asBoolean()).isTrue();

            JsonNode file = records.get(1).value();
            assertThat(file.get("studentId").asString()).as("A was already present").isEqualTo(B);
            assertThat(file.get("source").asString()).isEqualTo("FILE");
            assertThat(file.get("markId").asString()).isNotEqualTo(rfid.get("markId").asString());
        }

        Map<String, Object> props = KafkaTestUtils.consumerProps(broker, "dlt-reader", false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (Consumer<String, String> dlt = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(),
                new StringDeserializer()).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(dlt, Topics.IMPORT_EVENTS_DLT);
            List<ConsumerRecord<String, String>> dead = poll(dlt, 1);
            assertThat(dead).hasSize(1);
            assertThat(dead.get(0).value()).isEqualTo("not json");
            assertThat(dead.get(0).key()).isEqualTo("41");
        }
    }
}
