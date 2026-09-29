package com.quorum.placementservice.event;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record DriveScheduledEvent(
        Long driveId,
        String companyName,
        LocalDate driveDate,
        LocalTime startTime,
        LocalTime endTime,
        String venue) {}
