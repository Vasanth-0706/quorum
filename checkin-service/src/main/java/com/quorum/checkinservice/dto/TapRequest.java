package com.quorum.checkinservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/** POST /checkins/tap. tappedAt is optional (ISO-8601 with offset); without it the server time is used. */
public record TapRequest(@NotBlank @Size(max = 40) String cardUid, OffsetDateTime tappedAt) {
}
