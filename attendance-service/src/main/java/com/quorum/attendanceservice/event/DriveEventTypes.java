package com.quorum.attendanceservice.event;

import java.util.Set;

public final class DriveEventTypes {

    public static final String DRIVE_SCHEDULED = "DRIVE_SCHEDULED";
    public static final String ROUND_CREATED = "ROUND_CREATED";
    public static final String SESSION_SCHEDULED = "SESSION_SCHEDULED";
    public static final String SESSION_UPDATED = "SESSION_UPDATED";
    public static final String SESSION_CANCELLED = "SESSION_CANCELLED";

    public static final Set<String> SESSION_TYPES = Set.of(SESSION_SCHEDULED, SESSION_UPDATED, SESSION_CANCELLED);
    public static final Set<String> HANDLED = Set.of(DRIVE_SCHEDULED, ROUND_CREATED,
            SESSION_SCHEDULED, SESSION_UPDATED, SESSION_CANCELLED);

    private DriveEventTypes() {
    }
}
