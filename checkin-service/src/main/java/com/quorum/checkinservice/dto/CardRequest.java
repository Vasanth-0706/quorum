package com.quorum.checkinservice.dto;

import com.quorum.checkinservice.service.StudentIds;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** POST /cards. cardUid may use any case and ':' '-' or spaces; it is stored as upper-case hex. */
public record CardRequest(
        @NotNull @Pattern(regexp = StudentIds.REGEX, message = "must be exactly 10 digits") String studentId,
        @NotBlank @Size(max = 40) String cardUid) {
}
