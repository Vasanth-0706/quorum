package com.quorum.checkinservice.dto;

/** One shortlisted student of a round; name is null when no upload has named them. */
public record RosterStudentResponse(String studentId, String name) {
}
