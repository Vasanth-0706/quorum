package com.quorum.attendanceservice.event;

import com.quorum.attendanceservice.config.Topics;
import com.quorum.attendanceservice.repository.SessionViewRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** In-JVM Kafka (no Docker): good messages land in session_view, bad ones in drive-events.DLT. */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {Topics.DRIVE_EVENTS, Topics.DRIVE_EVENTS_DLT, Topics.CLASH_EVENTS,
        Topics.CHECKIN_EVENTS, Topics.IMPORT_EVENTS})
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:attendance-kafka-drive;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.listener.auto-startup=true",
        "quorum.kafka.retry.interval-ms=10"
})
class DriveEventsKafkaTest {

    @Autowired
    EmbeddedKafkaBroker broker;
    @Autowired
    SessionViewRepository sessions;

    private KafkaProducer<String, String> producer;
    private Consumer<String, String> dltConsumer;

    @BeforeEach
    void setUp() {
        Map<String, Object> props = KafkaTestUtils.producerProps(broker);
        producer = new KafkaProducer<>(props, new StringSerializer(), new StringSerializer());
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(broker, "dlt-reader", false);
        consumerProps.put("auto.offset.reset", "earliest");
        dltConsumer = new DefaultKafkaConsumerFactory<>(consumerProps, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
        broker.consumeFromAnEmbeddedTopic(dltConsumer, Topics.DRIVE_EVENTS_DLT);
    }

    @AfterEach
    void tearDown() {
        producer.close();
        dltConsumer.close();
    }

    private void send(String key, String value) throws Exception {
        producer.send(new ProducerRecord<>(Topics.DRIVE_EVENTS, key, value)).get();
    }

    @Test
    void badMessagesGoToDltAndDoNotBlockTheGoodOnes() throws Exception {
        send("5", "this is not json");
        send("5", """
                {"eventId":"bad-1","eventType":"SESSION_SCHEDULED","driveId":5,"roundId":11}""");
        send("5", """
                {"eventId":"ok-1","eventType":"SESSION_SCHEDULED","schemaVersion":1,"occurredAt":"2026-11-27T10:00:00.000Z",
                 "driveId":5,"roundId":11,"sessionId":77,"date":"2026-12-01","startTime":"09:00:00",
                 "endTime":"10:00:00","room":"Lab 1","status":"SCHEDULED"}""");
        send("5", """
                {"eventId":"ok-1","eventType":"SESSION_SCHEDULED","schemaVersion":1,"occurredAt":"2026-11-27T10:00:00.000Z",
                 "driveId":5,"roundId":11,"sessionId":77,"date":"2026-12-01","startTime":"09:00:00",
                 "endTime":"10:00:00","room":"Lab 1","status":"SCHEDULED"}""");

        List<ConsumerRecord<String, String>> dead = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 20_000;
        while (dead.size() < 2 && System.currentTimeMillis() < deadline) {
            KafkaTestUtils.getRecords(dltConsumer, Duration.ofMillis(500)).forEach(dead::add);
        }
        assertThat(dead).extracting(ConsumerRecord::value)
                .containsExactly("this is not json", "{\"eventId\":\"bad-1\",\"eventType\":\"SESSION_SCHEDULED\",\"driveId\":5,\"roundId\":11}");
        assertThat(dead).allSatisfy(r -> assertThat(r.key()).isEqualTo("5"));
        assertThat(dead.get(1).headers().lastHeader("kafka_dlt-exception-fqcn")).isNotNull();

        deadline = System.currentTimeMillis() + 10_000;
        while (sessions.findById(77L).isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(sessions.findById(77L)).isPresent();
        assertThat(sessions.count()).isEqualTo(1);
    }
}
