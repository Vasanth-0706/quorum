package com.quorum.placementservice;

import com.quorum.placementservice.entity.OutboxEvent;
import com.quorum.placementservice.repository.DriveRepository;
import com.quorum.placementservice.repository.OutboxEventRepository;
import com.quorum.placementservice.service.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Whole service on H2: entity rows and outbox rows are written together. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlacementFlowIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    OutboxEventRepository outbox;
    @Autowired
    DriveRepository drives;
    @MockitoSpyBean
    OutboxService outboxService;

    @BeforeEach
    void clean() {
        outbox.deleteAll();
    }

    private static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b, String body) {
        b.header("X-User-Id", "coord-1").header("X-User-Role", "COORDINATOR");
        return body == null ? b : b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private long createdId(MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(request).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asLong();
    }

    @Test
    void driveRoundsSessionsAndCancelWriteOrderedOutboxRows() throws Exception {
        long driveId = createdId(write(post("/drives"), """
                {"companyName":"Acme Corp","driveDate":"2026-12-01","startTime":"09:00","endTime":"17:00","venue":"Main Hall"}"""));
        long online = createdId(write(post("/drives/" + driveId + "/rounds"), """
                {"name":"Online test","sequence":1}"""));
        long tech = createdId(write(post("/drives/" + driveId + "/rounds"), """
                {"name":"Technical","sequence":2}"""));
        mvc.perform(write(post("/drives/" + driveId + "/rounds"), """
                        {"name":"Duplicate","sequence":2}"""))
                .andExpect(status().isConflict());

        createdId(write(post("/rounds/" + online + "/sessions"), """
                {"date":"2026-12-01","startTime":"09:00","endTime":"10:00","room":"Lab 1"}"""));
        long s2 = createdId(write(post("/rounds/" + tech + "/sessions"), """
                {"date":"2026-12-01","startTime":"11:00","endTime":"12:00","room":"Room 101"}"""));
        createdId(write(post("/rounds/" + tech + "/sessions"), """
                {"date":"2026-12-02","startTime":"09:30","endTime":"11:00","room":"Room 101"}"""));

        mvc.perform(write(post("/sessions/" + s2 + "/cancel"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(write(post("/sessions/" + s2 + "/cancel"), null)).andExpect(status().isConflict());
        mvc.perform(write(put("/sessions/" + s2), """
                        {"date":"2026-12-03","startTime":"09:00","endTime":"10:00","room":"Lab 9"}"""))
                .andExpect(status().isConflict());

        mvc.perform(get("/drives/" + driveId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rounds.length()").value(2))
                .andExpect(jsonPath("$.rounds[0].name").value("Online test"))
                .andExpect(jsonPath("$.rounds[1].sessions.length()").value(2))
                .andExpect(jsonPath("$.rounds[1].sessions[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.rounds[1].sessions[1].date").value("2026-12-02"));

        List<OutboxEvent> rows = outbox.findAll().stream()
                .sorted((a, b) -> Long.compare(a.getId(), b.getId())).toList();
        assertThat(rows).extracting(OutboxEvent::getEventType).containsExactly(
                "DRIVE_SCHEDULED", "ROUND_CREATED", "ROUND_CREATED",
                "SESSION_SCHEDULED", "SESSION_SCHEDULED", "SESSION_SCHEDULED", "SESSION_CANCELLED");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getTopic()).isEqualTo("drive-events");
            assertThat(r.getAggregateKey()).isEqualTo(String.valueOf(driveId));
            assertThat(r.getPublishedAt()).isNull();
            JsonNode p = json.readTree(r.getPayload());
            assertThat(p.get("eventType").asString()).isEqualTo(r.getEventType());
            assertThat(p.get("schemaVersion").asInt()).isEqualTo(1);
            assertThat(p.get("eventId").asString()).hasSize(36);
            assertThat(p.get("occurredAt").asString()).endsWith("Z");
            assertThat(p.get("driveId").asLong()).isEqualTo(driveId);
        });
        JsonNode cancelled = json.readTree(rows.get(6).getPayload());
        assertThat(cancelled.get("sessionId").asLong()).isEqualTo(s2);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("room").asString()).isEqualTo("Room 101");
    }

    @Test
    void driveIsNotSavedWhenItsOutboxRowFails() throws Exception {
        long before = drives.count();
        // Stub the target behind the transactional proxy, so stubbing itself needs no transaction
        OutboxService target = AopTestUtils.getUltimateTargetObject(outboxService);
        doThrow(new IllegalStateException("outbox down"))
                .when(target).append(anyString(), anyString(), anyString(), any());

        mvc.perform(write(post("/drives"), """
                        {"companyName":"Rollback Ltd","driveDate":"2026-12-05","startTime":"09:00","endTime":"10:00","venue":"Hall"}"""))
                .andExpect(status().isInternalServerError());

        assertThat(drives.count()).isEqualTo(before);
        assertThat(outbox.count()).isZero();
    }

    @Test
    void listIsNewestFirst() throws Exception {
        long first = createdId(write(post("/drives"), """
                {"companyName":"First","driveDate":"2026-12-01","startTime":"09:00","endTime":"10:00","venue":"Hall"}"""));
        long second = createdId(write(post("/drives"), """
                {"companyName":"Second","driveDate":"2026-12-01","startTime":"09:00","endTime":"10:00","venue":"Hall"}"""));
        mvc.perform(get("/drives"))
                .andExpect(jsonPath("$[0].id").value(second))
                .andExpect(jsonPath("$[1].id").value(first));
    }
}
