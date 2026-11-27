package com.quorum.attendanceservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** POST /corrections: set a student's mark in a session. reason is required. */
public record CorrectionRequest(
        @NotNull @Positive Long sessionId,
        @NotNull @Pattern(regexp = "\\d{10}", message = "must be exactly 10 digits") String studentId,
        @NotNull Boolean present,
        @NotBlank @Size(max = 500) String reason) {
}
