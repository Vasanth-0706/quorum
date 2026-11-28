package com.quorum.checkinservice.controller;

import com.quorum.checkinservice.dto.AttendanceSheetResponse;
import com.quorum.checkinservice.dto.RosterStudentResponse;
import com.quorum.checkinservice.service.AttendanceSheetService;
import com.quorum.checkinservice.service.RosterService;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only, like every GET here: no X-User headers needed. */
@RestController
public class RosterController {

    private final RosterService rosters;
    private final AttendanceSheetService sheets;

    public RosterController(RosterService rosters, AttendanceSheetService sheets) {
        this.rosters = rosters;
        this.sheets = sheets;
    }

    /** The round's shortlisted students by studentId; an empty list when none was uploaded. */
    @GetMapping("/rounds/{roundId}/roster")
    public List<RosterStudentResponse> roster(@PathVariable @Positive long roundId) {
        return rosters.roster(roundId);
    }

    /** 404 SESSION_NOT_FOUND when the session has not arrived from drive-events. */
    @GetMapping("/sessions/{sessionId}/attendance-sheet")
    public AttendanceSheetResponse sheet(@PathVariable @Positive long sessionId) {
        return sheets.sheet(sessionId);
    }
}
