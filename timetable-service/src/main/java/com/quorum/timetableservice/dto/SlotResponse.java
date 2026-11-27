package com.quorum.timetableservice.dto;

import com.quorum.timetableservice.entity.Slot;

import java.time.DayOfWeek;
import java.time.LocalTime;

public record SlotResponse(String sectionCode, String courseName, DayOfWeek dayOfWeek, LocalTime startTime,
                           LocalTime endTime, String room) {

    public static SlotResponse from(Slot s) {
        return new SlotResponse(s.getSectionCode(), s.getCourseName(), s.getDayOfWeek(), s.getStartTime(),
                s.getEndTime(), s.getRoom());
    }
}
