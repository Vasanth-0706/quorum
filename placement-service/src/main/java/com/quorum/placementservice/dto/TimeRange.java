package com.quorum.placementservice.dto;

import java.time.LocalTime;

/** A request with a start and end time, checked by {@link ValidTimeRange}. */
public interface TimeRange {

    LocalTime startTime();

    LocalTime endTime();
}
