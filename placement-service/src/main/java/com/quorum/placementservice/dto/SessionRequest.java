package com.quorum.placementservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;

/** Body of POST /rounds/{roundId}/sessions and PUT /sessions/{id}. */
@ValidTimeRange
public record SessionRequest(
        @NotNull LocalDate date,
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime,
        @NotBlank @Size(max = 60) String room) implements TimeRange {
}
