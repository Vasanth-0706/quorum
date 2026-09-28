package com.quorum.placementservice.event;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record DriveScheduledEvent(    Long driveId,
        String dirveName,
        LocalDate driveDate,
        LocalTime startTime,
        LocalDateTime endTime,
        String venue) {

}
