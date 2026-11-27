package com.quorum.attendanceservice.service;

import com.quorum.attendanceservice.config.KafkaConsumerConfig;
import com.quorum.attendanceservice.config.Topics;
import com.quorum.attendanceservice.repository.AttendanceMarkRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * By default (no property set) the TEMPORARY bridge is off: import-events is not subscribed and
 * ATTENDANCE_UPLOADED does nothing, because checkin-service sends file uploads as ATTENDANCE_MARKED.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:attendance-no-bridge;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class FileBridgeDisabledTest {

    @Autowired
    @Qualifier(KafkaConsumerConfig.INBOUND_TOPICS_BEAN)
    String[] inboundTopics;
    @Autowired
    InboundEventHandler handler;
    @Autowired
    AttendanceMarkRepository marks;

    @Test
    void importEventsAreNeitherReadNorApplied() {
        assertThat(inboundTopics).containsExactlyInAnyOrder(Topics.DRIVE_EVENTS, Topics.CLASH_EVENTS,
                Topics.CHECKIN_EVENTS);

        InboundEventHandler.Outcome outcome = handler.handle(Topics.IMPORT_EVENTS, JsonMapper.builder().build().readTree("""
                {"eventId":"a-1","eventType":"ATTENDANCE_UPLOADED","uploadId":"u-1","sessionId":41,
                 "studentIds":["9900000001"]}"""));

        assertThat(outcome).isEqualTo(InboundEventHandler.Outcome.IGNORED);
        assertThat(marks.count()).isZero();
    }
}
