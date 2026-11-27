package com.quorum.checkinservice.dto;

import com.quorum.checkinservice.service.StudentIds;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ManualMarkRequest(
        @NotNull @Pattern(regexp = StudentIds.REGEX, message = "must be exactly 10 digits") String studentId,
        @NotNull Boolean present) {
}
