package com.quorum.timetableservice.controller;

import com.quorum.timetableservice.dto.SemesterResponse;
import com.quorum.timetableservice.dto.StudentSlotsResponse;
import com.quorum.timetableservice.service.TimetableQueryService;
import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/timetable")
public class TimetableController {

    private final TimetableQueryService queries;

    public TimetableController(TimetableQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/semesters")
    public List<SemesterResponse> semesters() {
        return queries.semesters();
    }

    @GetMapping("/students/{studentId}/slots")
    public StudentSlotsResponse studentSlots(
            @PathVariable @Pattern(regexp = "\\d{10}", message = "must be exactly 10 digits") String studentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return queries.studentSlots(studentId, date);
    }
}
