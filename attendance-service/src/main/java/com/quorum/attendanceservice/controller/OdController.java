package com.quorum.attendanceservice.controller;

import com.quorum.attendanceservice.dto.MarkResponse;
import com.quorum.attendanceservice.dto.OdRecordResponse;
import com.quorum.attendanceservice.service.OdQueryService;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/** Read-only. By default only GRANTED records are listed; includeRevoked=true adds the REVOKED ones. */
@RestController
public class OdController {

    static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final OdQueryService queries;

    public OdController(OdQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/od")
    public List<OdRecordResponse> byDate(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                         @RequestParam(defaultValue = "false") boolean includeRevoked) {
        return queries.byDate(date, includeRevoked);
    }

    @GetMapping("/od/students/{studentId}")
    public List<OdRecordResponse> byStudent(
            @PathVariable @Pattern(regexp = "\\d{10}", message = "must be exactly 10 digits") String studentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean includeRevoked) {
        return queries.byStudent(studentId, from, to, includeRevoked);
    }

    @GetMapping("/od/sessions/{sessionId}")
    public List<OdRecordResponse> bySession(@PathVariable @Positive Long sessionId,
                                            @RequestParam(defaultValue = "false") boolean includeRevoked) {
        return queries.bySession(sessionId, includeRevoked);
    }

    @GetMapping("/od/export")
    public ResponseEntity<String> export(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("od-" + date + ".csv").build().toString())
                .body(queries.exportCsv(date));
    }

    /** Latest mark per student; history=true lists every version. */
    @GetMapping("/marks/sessions/{sessionId}")
    public List<MarkResponse> marks(@PathVariable @Positive Long sessionId,
                                    @RequestParam(defaultValue = "false") boolean history) {
        return queries.marks(sessionId, history);
    }
}
