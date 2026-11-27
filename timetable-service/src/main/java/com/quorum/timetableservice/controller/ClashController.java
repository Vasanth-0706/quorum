package com.quorum.timetableservice.controller;

import com.quorum.timetableservice.dto.ClashResponse;
import com.quorum.timetableservice.service.TimetableQueryService;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
public class ClashController {

    private final TimetableQueryService queries;

    public ClashController(TimetableQueryService queries) {
        this.queries = queries;
    }

    /** date is optional: without it, every date the round has sessions on. */
    @GetMapping("/clashes")
    public List<ClashResponse> clashes(
            @RequestParam @Positive Long roundId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return queries.clashes(roundId, date);
    }
}
