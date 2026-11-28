package com.quorum.checkinservice.event;

import com.quorum.checkinservice.MutableClock;
import com.quorum.checkinservice.config.Topics;
import com.quorum.checkinservice.repository.RosterEntryRepository;
import com.quorum.checkinservice.repository.SessionViewRepository;
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
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * In-JVM Kafka: SHORTLIST_UPLOADED and ATTENDANCE_UPLOADED exactly as import-service v0.2.1 encodes
 * them (printed by {@code publisher.encode(publisher.shortlist_event(...))}, schemaVersion,
 * occurredAt and students[] included) build the roster, the names and the FILE marks. A SHORTLIST
 * whose studentIds[] holds a bad ID goes to import-events.DLT and does not block the partition.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MutableClock.Config.class)
@EmbeddedKafka(partitions = 1, topics = {Topics.DRIVE_EVENTS, Topics.IMPORT_EVENTS, Topics.CHECKIN_EVENTS,
        Topics.DRIVE_EVENTS_DLT, Topics.IMPORT_EVENTS_DLT})
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:checkin-roster-kafka;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.listener.auto-startup=true",
        "quorum.outbox.relay.enabled=true",
        "quorum.outbox.relay-interval=100ms",
        "quorum.kafka.retry.interval-ms=10"
})
class RosterKafkaEndToEndTest {

    /** Verbatim output of import-service's publisher for a 3-student shortlist of round 7. */
    static final String SHORTLIST = """
            {"eventId":"ba621dbf-1ff5-4844-a525-a86153f5c4b2","eventType":"SHORTLIST_UPLOADED","schemaVersion":1,"occurredAt":"2026-11-28T17:52:32.162Z","uploadId":"6a82977c-2840-4752-83d0-d1ce0ae3492d","roundId":7,"studentIds":["9900000301","9900000302","9900000303"],"students":[{"studentId":"9900000301","name":"Tony Stark"},{"studentId":"9900000302","name":null},{"studentId":"9900000303","name":"Bruce Wayne"}],"uploadedAt":"2026-12-07T09:00:00"}""";

    /** Verbatim output for an attendance file of session 41 (one student is not on the roster). */
    static final String ATTENDANCE = """
            {"eventId":"332332e1-2f4f-4a40-aa7a-59de1d8ba948","eventType":"ATTENDANCE_UPLOADED","schemaVersion":1,"occurredAt":"2026-11-28T17:52:32.162Z","uploadId":"97815a70-e0d2-4a5e-a79b-1a2ac7b343b2","sessionId":41,"studentIds":["9900000301","9900000309"],"students":[{"studentId":"9900000301","name":"Anthony Stark"},{"studentId":"9900000309","name":"Peter Parker"}],"uploadedAt":"2026-12-07T11:48:00"}""";

    static final String BAD_SHORTLIST = """
            {"eventId":"bad-1","eventType":"SHORTLIST_UPLOADED","schemaVersion":1,"uploadId":"u-bad","roundId":8,"studentIds":["123"],"students":[],"uploadedAt":"2026-12-07T09:00:00"}""";

    @Autowired
    EmbeddedKafkaBroker broker;
    @Autowired
    MockMvc mvc;
    @Autowired
    SessionViewRepository sessions;
    @Autowired
    RosterEntryRepository roster;

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }

    @Test
    void importServiceEventsBuildTheRosterAndTheSheet() throws Exception {
        try (KafkaProducer<String, String> p = new KafkaProducer<>(KafkaTestUtils.producerProps(broker),
                new StringSerializer(), new StringSerializer())) {
            p.send(new ProducerRecord<>(Topics.DRIVE_EVENTS, "3", """
                    {"eventId":"d-1","eventType":"SESSION_SCHEDULED","schemaVersion":1,"occurredAt":"2026-11-27T10:00:00.000Z",
                     "driveId":3,"roundId":7,"sessionId":41,"date":"2026-12-07","startTime":"09:30:00","endTime":"11:30:00",
                     "room":"C-301","status":"SCHEDULED"}""")).get();
            p.send(new ProducerRecord<>(Topics.IMPORT_EVENTS, "8", BAD_SHORTLIST)).get();
            p.send(new ProducerRecord<>(Topics.IMPORT_EVENTS, "7", SHORTLIST)).get();
        }
        await(() -> sessions.findById(41L).isPresent(), "session 41 from drive-events");
        await(() -> roster.findByRoundIdOrderByStudentIdAsc(7L).size() == 3, "round 7 roster from import-events");

        try (KafkaProducer<String, String> p = new KafkaProducer<>(KafkaTestUtils.producerProps(broker),
                new StringSerializer(), new StringSerializer())) {
            p.send(new ProducerRecord<>(Topics.IMPORT_EVENTS, "41", ATTENDANCE)).get();
            p.send(new ProducerRecord<>(Topics.IMPORT_EVENTS, "7", SHORTLIST)).get(); // redelivery: no change
        }

        mvc.perform(get("/rounds/7/roster"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].name").value("Tony Stark"))
                .andExpect(jsonPath("$[1].name").value(nullValue()))
                .andExpect(jsonPath("$[2].name").value("Bruce Wayne"));

        long deadline = System.currentTimeMillis() + 20_000;
        String body = "";
        while (System.currentTimeMillis() < deadline) {
            body = mvc.perform(get("/sessions/41/attendance-sheet")).andReturn().getResponse().getContentAsString();
            if (body.contains("\"present\":2")) {
                break;
            }
            Thread.sleep(100);
        }
        mvc.perform(get("/sessions/41/attendance-sheet"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.present").value(2))
                .andExpect(jsonPath("$.counts.notMarked").value(2))
                .andExpect(jsonPath("$.counts.total").value(4))
                .andExpect(jsonPath("$.students[0].studentId").value("9900000301"))
                .andExpect(jsonPath("$.students[0].name").value("Tony Stark"))
                .andExpect(jsonPath("$.students[0].source").value("FILE"))
                .andExpect(jsonPath("$.students[3].studentId").value("9900000309"))
                .andExpect(jsonPath("$.students[3].name").value("Peter Parker"))
                .andExpect(jsonPath("$.students[3].onShortlist").value(false));
        assertThat(roster.findByRoundIdOrderByStudentIdAsc(8L)).as("bad shortlist not applied").isEmpty();

        Map<String, Object> props = KafkaTestUtils.consumerProps(broker, "dlt-reader", false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (Consumer<String, String> dlt = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(),
                new StringDeserializer()).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(dlt, Topics.IMPORT_EVENTS_DLT);
            List<ConsumerRecord<String, String>> dead = new ArrayList<>();
            long until = System.currentTimeMillis() + 20_000;
            while (dead.isEmpty() && System.currentTimeMillis() < until) {
                KafkaTestUtils.getRecords(dlt, Duration.ofMillis(500)).forEach(dead::add);
            }
            assertThat(dead).hasSize(1);
            assertThat(dead.get(0).key()).isEqualTo("8");
            assertThat(dead.get(0).value()).contains("bad-1");
        }
    }
}
