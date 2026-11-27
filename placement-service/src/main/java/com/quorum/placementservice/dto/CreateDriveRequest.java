package com.quorum.placementservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;

@ValidTimeRange
public record CreateDriveRequest(
        @NotBlank @Size(max = 120) String companyName,
        @NotNull LocalDate driveDate,
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime,
        @NotBlank @Size(max = 120) String venue) implements TimeRange {
}
