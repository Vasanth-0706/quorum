package com.quorum.placementservice.dto;

import com.quorum.placementservice.entity.Drive;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** GET /drives/{id}: the drive with its rounds, each with its sessions. */
public record DriveDetailResponse(
        Long id,
        String companyName,
        LocalDate driveDate,
        LocalTime startTime,
        LocalTime endTime,
        String venue,
        List<RoundResponse> rounds) {

    public static DriveDetailResponse from(Drive d, List<RoundResponse> rounds) {
        return new DriveDetailResponse(d.getId(), d.getCompanyName(), d.getDriveDate(), d.getStartTime(),
                d.getEndTime(), d.getVenue(), rounds);
    }
}
