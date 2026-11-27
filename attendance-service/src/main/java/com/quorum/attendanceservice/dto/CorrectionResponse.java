package com.quorum.attendanceservice.dto;

import com.quorum.attendanceservice.entity.Correction;

import java.time.Instant;

/** oldPresent is null when the student had no mark before the correction. */
public record CorrectionResponse(Long id, Long sessionId, String studentId, Boolean oldPresent, boolean newPresent,
                                 String reason, String correctedBy, String role, Instant correctedAt,
                                 String markId, int markVersion) {

    public static CorrectionResponse from(Correction c) {
        return new CorrectionResponse(c.getId(), c.getSessionId(), c.getStudentId(), c.getOldPresent(),
                c.isNewPresent(), c.getReason(), c.getActorId(), c.getActorRole(), c.getCorrectedAt(),
                c.getMarkId(), c.getMarkVersion());
    }
}
