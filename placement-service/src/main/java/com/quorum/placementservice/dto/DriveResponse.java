package com.quorum.placementservice.dto;

import com.quorum.placementservice.entity.Drive;

import java.time.LocalDate;
import java.time.LocalTime;

public record DriveResponse(
        Long id,
        String companyName,
        LocalDate driveDate,
        LocalTime startTime,
        LocalTime endTime,
        String venue) {

    public static DriveResponse from(Drive d) {
        return new DriveResponse(d.getId(), d.getCompanyName(), d.getDriveDate(), d.getStartTime(),
                d.getEndTime(), d.getVenue());
    }
}
