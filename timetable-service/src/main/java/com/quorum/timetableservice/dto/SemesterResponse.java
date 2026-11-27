package com.quorum.timetableservice.dto;

import com.quorum.timetableservice.entity.Semester;

import java.time.Instant;

public record SemesterResponse(String semester, boolean active, String uploadId, int slotCount,
                               int enrolmentCount, Instant appliedAt) {

    public static SemesterResponse from(Semester s) {
        return new SemesterResponse(s.getCode(), s.isActive(), s.getUploadId(), s.getSlotCount(),
                s.getEnrolmentCount(), s.getAppliedAt());
    }
}
