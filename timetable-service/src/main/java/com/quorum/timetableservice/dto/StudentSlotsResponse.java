package com.quorum.timetableservice.dto;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/** The student's class slots on that date's day of week, in the active semester (null if none). */
public record StudentSlotsResponse(String studentId, LocalDate date, DayOfWeek dayOfWeek, String semester,
                                   List<SlotResponse> slots) {
}
