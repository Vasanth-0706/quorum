package com.quorum.placementservice.controller;

import com.quorum.placementservice.dto.CreateDriveRequest;
import com.quorum.placementservice.dto.DriveResponse;
import com.quorum.placementservice.service.DriveService;
import com.quorum.placementservice.service.NotFoundException;
import com.quorum.placementservice.service.RoundService;
import com.quorum.placementservice.service.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP layer only: validation errors, temporary auth headers and error mapping. */
@WebMvcTest(controllers = {DriveController.class, SessionController.class})
class ControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    DriveService driveService;
    @MockitoBean
    RoundService roundService;
    @MockitoBean
    SessionService sessionService;

    private static MockHttpServletRequestBuilder asCoordinator(MockHttpServletRequestBuilder b) {
        return b.header("X-User-Id", "coord-1").header("X-User-Role", "COORDINATOR")
                .contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void createDriveReturns201WithLocation() throws Exception {
        when(driveService.create(any(CreateDriveRequest.class))).thenReturn(new DriveResponse(7L, "Acme",
                LocalDate.of(2026, 12, 1), LocalTime.of(9, 0), LocalTime.of(17, 0), "Main Hall"));

        mvc.perform(asCoordinator(post("/drives")).content("""
                        {"companyName":"Acme","driveDate":"2026-12-01","startTime":"09:00","endTime":"17:00","venue":"Main Hall"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/drives/7"))
                .andExpect(jsonPath("$.id").value(7));
    }

    @Test
    void invalidDriveReturns400WithFieldErrors() throws Exception {
        mvc.perform(asCoordinator(post("/drives")).content("""
                        {"companyName":" ","startTime":"17:00","endTime":"09:00","venue":""}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors", hasSize(4)))
                .andExpect(jsonPath("$.errors[?(@.field=='companyName')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='venue')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='driveDate')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='endTime')].message").value("must be after startTime"));
        verifyNoInteractions(driveService);
    }

    @Test
    void malformedValueReturns400NamingTheField() throws Exception {
        mvc.perform(asCoordinator(post("/drives")).content("""
                        {"companyName":"Acme","driveDate":"not-a-date","startTime":"09:00","endTime":"17:00","venue":"Hall"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("driveDate"));
    }

    @Test
    void missingBodyReturns400() throws Exception {
        mvc.perform(asCoordinator(post("/drives")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("not valid JSON")));
    }

    @Test
    void writeWithoutHeadersIs401() throws Exception {
        mvc.perform(post("/drives").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", containsString("X-User-Id")));
        mvc.perform(post("/sessions/1/cancel").header("X-User-Id", "u1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", containsString("X-User-Role")));
        verifyNoInteractions(driveService, sessionService);
    }

    @Test
    void writeWithWrongRoleIs403() throws Exception {
        mvc.perform(post("/sessions/1/cancel").header("X-User-Id", "u1").header("X-User-Role", "STUDENT"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(sessionService);
    }

    @Test
    void readsNeedNoHeaders() throws Exception {
        mvc.perform(get("/drives")).andExpect(status().isOk());
    }

    @Test
    void unknownDriveIs404() throws Exception {
        when(driveService.findDetail(99L)).thenThrow(new NotFoundException("Drive 99 not found"));
        mvc.perform(get("/drives/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Drive 99 not found"));
    }

    @Test
    void nonNumericIdIs400() throws Exception {
        mvc.perform(get("/drives/abc")).andExpect(status().isBadRequest());
    }

    @Test
    void invalidSessionReturns400() throws Exception {
        mvc.perform(asCoordinator(post("/rounds/3/sessions")).content("""
                        {"date":"2026-12-02","startTime":"11:00","endTime":"10:00","room":"Lab 1"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("endTime"));
        verifyNoInteractions(sessionService);
    }
}
